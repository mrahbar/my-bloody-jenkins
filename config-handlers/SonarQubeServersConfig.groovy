def asInt(value, defaultValue=0){
    return value ? value.toInteger() : defaultValue
}
def asBoolean(value, defaultValue=false){
    return value != null ? value.toBoolean() : defaultValue
}

def sonarInstallation(config){
    config.with{
        def sonarInstallation = new hudson.plugins.sonar.SonarInstallation(
            name,
            serverUrl,
            serverVersion?.toString(),
            serverAuthenticationToken,
            databaseUrl,
            databaseLogin,
            databasePassword,
            mojoVersion?.toString(),
            additionalProperties,
            new hudson.plugins.sonar.model.TriggersConfig(
                asBoolean(triggers?.skipScmCause),
                asBoolean(triggers?.skipUpstreamCause),
                triggers?.envVar
            ),
            sonarLogin,
            sonarPassword,
            additionalAnalysisProperties
        )
    }
}

def setup(config){
    config = config ?: [:]
    def sonarGlobalConfig = jenkins.model.Jenkins.instance.getDescriptor(hudson.plugins.sonar.SonarGlobalConfiguration)
    def installations = config.installations?.collect{k,v ->
        sonarInstallation([name: k] << v)
    }
    if(installations){
        sonarGlobalConfig.setInstallations(installations.toArray(sonarGlobalConfig.installations))
    }
    sonarGlobalConfig.buildWrapperEnabled = asBoolean(config.buildWrapperEnabled)
    sonarGlobalConfig.save()

}
return this