import hudson.security.LDAPSecurityRealm
import hudson.security.GlobalMatrixAuthorizationStrategy
import hudson.security.HudsonPrivateSecurityRealm
import jenkins.security.plugins.ldap.FromGroupSearchLDAPGroupMembershipStrategy
import jenkins.security.plugins.ldap.FromUserRecordLDAPGroupMembershipStrategy
import jenkins.model.Jenkins
import hudson.model.Hudson

def asInt(value, defaultValue=0){
    return value ? value.toInteger() : defaultValue
}
def asBoolean(value, defaultValue=false){
    return value != null ? value.toBoolean() : defaultValue
}

def setupLdap(config){
    config.with{
        if (!groupMembershipAttribute && !groupMembershipFilter){
            throw new IllegalArgumentException("One of: groupMembershipFilter, groupMembershipAttribute must be provided")
        }

        def groupMembershipStrategy = groupMembershipAttribute ?
            new FromUserRecordLDAPGroupMembershipStrategy(groupMembershipAttribute) :
            new FromGroupSearchLDAPGroupMembershipStrategy(groupMembershipFilter)
        return new LDAPSecurityRealm(
                server,
                rootDN,
                userSearchBase,
                userSearchFilter,
                groupSearchBase,
                groupSearchFilter,
                groupMembershipStrategy,
                managerDN,
                hudson.util.Secret.fromString(managerPassword),
                asBoolean(inhibitInferRootDN),
                asBoolean(disableMailAddressResolver),
                new LDAPSecurityRealm.CacheConfiguration(20, 300), [
                    new LDAPSecurityRealm.EnvironmentProperty('com.sun.jndi.ldap.connect.timeout', asInt(connectTimeout, 5000).toString()),
                    new LDAPSecurityRealm.EnvironmentProperty('com.sun.jndi.ldap.read.timeout', asInt(readTimeout, 60000).toString()),
                ] as LDAPSecurityRealm.EnvironmentProperty[],
                displayNameAttr,
                emailAttr,
                /*IdStrategy userIdStrategy*/null,
                /*IdStrategy groupIdStrategy*/null
        )
    }
}

def setupJenkinsDatabase(config){
    def currnetRealm = jenkins.model.Jenkins.instance.securityRealm
    def securityRealm = (currnetRealm instanceof HudsonPrivateSecurityRealm) ? currnetRealm : new HudsonPrivateSecurityRealm(false)
    config.with{
        securityRealm.createAccount(adminUser, adminPassword)
    }
    return securityRealm
}

def createAuthorizationStrategy(config, adminUser){
    def strategy = new GlobalMatrixAuthorizationStrategy()

    for (user in adminUser) {
        strategy.add(Hudson.ADMINISTER, user)
    }

    config?.permissions?.each{ principal, permissions ->
        for(p in permissions){
            try{
                def permission = hudson.security.Permission.fromId(p)
                strategy.add(permission, principal)
            }catch(e){
                println "Failed to set permission ${p} for principal ${principal}... ${e}"
                e.printStackTrace()
            }
        }
    }
    return strategy
}

def setupSecurityOptions(config){
    config = config ?: [:]
    // https://wiki.jenkins.io/display/JENKINS/CSRF+Protection
    config.preventCSRF = asBoolean(config.preventCSRF, true)
    config.enableScriptSecurityForDSL = asBoolean(config.enableScriptSecurityForDSL)
    // See https://jenkins.io/blog/2017/04/11/new-cli/
    config.enableCLIOverRemoting = asBoolean(config.enableCLIOverRemoting)
    // See https://wiki.jenkins.io/display/JENKINS/Slave+To+Master+Access+Control
    config.enableAgentMasterAccessControl = asBoolean(config.enableAgentMasterAccessControl, true)
    config.disableRememberMe = asBoolean(config.disableRememberMe)
    config.sshdEnabled = asBoolean(config.sshdEnabled)
    config.jnlpProtocols = config.jnlpProtocols != null ? config.jnlpProtocols : ['JNLP4']

    config.with{
        if(preventCSRF){
            jenkins.model.Jenkins.instance.crumbIssuer = new hudson.security.csrf.DefaultCrumbIssuer(true)
        }else{
            jenkins.model.Jenkins.instance.setCrumbIssuer(null)
        }
        jenkins.model.GlobalConfiguration.all()
            .get(javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration)
            .useScriptSecurity = enableScriptSecurityForDSL
        jenkins.CLI.get().enabled = enableCLIOverRemoting
        jenkins.model.Jenkins.instance.disableRememberMe = disableRememberMe
        jenkins.model.Jenkins.instance
            .injector.getInstance(jenkins.security.s2m.AdminWhitelistRule).masterKillSwitch = !enableAgentMasterAccessControl

        jenkins.model.Jenkins.instance.agentProtocols = jnlpProtocols.collect{"${it}-connect".toString()} as Set

        if(sshdEnabled){
            org.jenkinsci.main.modules.sshd.SSHD.get().port = 16022
        }else{
            org.jenkinsci.main.modules.sshd.SSHD.get().port = -1
        }
        jenkins.model.Jenkins.instance.save()
    }
}

def setup(config){
    config = config ?: [:]
    def instance = Jenkins.getInstance()

    def realm
    switch(config.realm){
        case 'ldap':
            realm = setupLdap(config)
            break
        case 'jenkins_database':
            realm = setupJenkinsDatabase(config)
            break
    }
    if(realm){
        instance.setSecurityRealm(realm)
        def strategy = createAuthorizationStrategy(config, config.adminUsers)
        instance.setAuthorizationStrategy(strategy)
        instance.save()
    }

    setupSecurityOptions(config.securityOptions)
}

return this