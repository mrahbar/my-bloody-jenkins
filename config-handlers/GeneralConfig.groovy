import jenkins.model.Jenkins
import hudson.security.csrf.DefaultCrumbIssuer
import jenkins.CLI

def asInt(value, defaultValue=0){
    return value ? value.toInteger() : defaultValue
}
def asBoolean(value, defaultValue=false){
    return value != null ? value.toBoolean() : defaultValue
}

def setup(config){
    def env = System.getenv()
    def instance = Jenkins.getInstance()
    def slaveAgentPorts = env['JENKINS_SLAVE_AGENT_PORT']
    def executersCount = env['JENKINS_ENV_EXECUTERS']
    def cliOverRemoting = env['JENKINS_ENV_CLI_REMOTING_ENABLED']
    def csrfProtection = env['JENKINS_ENV_CSRF_PROTECTION_ENABLED']
    def jnlpKillSwitch = env['JENKINS_ENV_JNLP_KILL_SWITCH']
    def useScriptSecurity = env['JENKINS_ENV_USE_SCRIPT_SECURITY']
    def changeWorkspaceDir = env['JENKINS_ENV_CHANGE_WORKSPACE_DIR']

    def jenkinsUrl = env['JENKINS_ENV_JENKINS_URL']
    def adminAddress = env['JENKINS_ENV_ADMIN_ADDRESS']

    if(slaveAgentPorts){
        Jenkins.instance.setSlaveAgentPort(asInt(slaveAgentPorts, 50000))
        Jenkins.instance.save()
    }

    if(jenkinsUrl || adminAddress){
        def jenkinsLocationConfig = jenkins.model.JenkinsLocationConfiguration.get()
        if(jenkinsUrl){
            jenkinsLocationConfig.url  = jenkinsUrl
        }
        if(adminAddress){
            jenkinsLocationConfig.adminAddress = adminAddress
        }
        jenkinsLocationConfig.save()
    }

    if(csrfProtection) {
        instance.setCrumbIssuer(new DefaultCrumbIssuer(true))
        instance.save()
    }

    if(jnlpKillSwitch) {
        // Disable jnlp
        instance.setSlaveAgentPort(-1);

        // Disable old Non-Encrypted protocols
        def protocols = instance.getAgentProtocols()
        println 'Agent protocols: '+protocols
        HashSet<String> newProtocols = new HashSet<>(protocols);
        newProtocols.removeAll(Arrays.asList(
                "JNLP3-connect", "JNLP2-connect", "JNLP-connect", "CLI-connect"
        ));
        instance.setAgentProtocols(newProtocols);
        instance.save()
    }

    instance.setNumExecutors(executersCount  ? executersCount.toInteger() : 0)
    CLI.get().setEnabled(cliOverRemoting ? cliOverRemoting.toBoolean() : false)

    // This is the only way to change the workspaceDir field at the moment... ):
    // We do that if the JENKINS_HOME is mapped to NFS volume (e.g. deployment on ECS or Kubernetes)
    if(changeWorkspaceDir){
        def f = Jenkins.getDeclaredField('workspaceDir')
        f.setAccessible(true)
        f.set(Jenkins.instance, '/jenkins-workspace-home/workspace/${ITEM_FULLNAME}')
        Jenkins.instance.save()
    }


    jenkins.model.GlobalConfiguration.all()
        .get(javaposse.jobdsl.plugin.GlobalJobDslSecurityConfiguration).useScriptSecurity =
            useScriptSecurity ? useScriptSecurity.toBoolean() : false

    Thread.start{
        sleep 1000
        println 'updating Downloadables'
        hudson.model.DownloadService.Downloadable.all().each{ d -> d.updateNow() }
        println 'updating Downloadables. done...'
    }

}
return this