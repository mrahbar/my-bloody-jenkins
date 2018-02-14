import org.yaml.snakeyaml.Yaml
import jenkins.security.ApiTokenProperty
import hudson.model.User

def loadYamlConfig(filename){
    return new File(filename).withReader{
        new Yaml().load(it)
    }
}

def handleConfig(handler, config){
    if(!config){
        println "[JenkinsConfigLoader] --> skipping ${handler} configuration"
        return
    }
    println "[JenkinsConfigLoader] --> Handling ${handler} configuration"
    try{
        evaluate(new File("/usr/share/jenkins/config-handlers/${handler}Config.groovy")).setup(config)
        println "[JenkinsConfigLoader] --> Handling ${handler} configuration... done"
    }catch(e){
        println "[JenkinsConfigLoader] --> Handling ${handler} configuration... error: ${e}"
        e.printStackTrace()
    }
}

def masterUser = System.getenv()['JENKINS_ENV_INITIAL_MASTER_USER']
if(!masterUser){
    println "[JenkinsConfigLoader] JENKINS_ENV_INITIAL_MASTER_USER was not set. This is mandatory variable"
}else{
    storeAdminApiToken(masterUser, System.getenv()['TOKEN_FILE_LOCATION'])
}

def storeAdminApiToken(adminUser, filename){
    def adminUserApiToken = User.get(adminUser, true)?.getProperty(ApiTokenProperty)?.apiTokenInsecure
    if(adminUserApiToken){
        new File(filename).withWriter{out -> out.println "[JenkinsConfigLoader] ${adminUser}:${adminUserApiToken}"}
    }
}

def configFileName = System.getenv()['CONFIG_FILE_LOCATION']

if(!new File(configFileName).exists()) {
    println "[JenkinsConfigLoader] ${configFileName} does not exist. Set variable JENKINS_ENV_CONFIG_YAML! Skipping configuration..."
} else {
    def jenkinsConfig = loadYamlConfig(configFileName)
    println "[JenkinsConfigLoader] Loaded yaml config starting configs:"

    handleConfig('Proxy', jenkinsConfig.proxy)
    handleConfig('General', [general: true])
    handleConfig('EnvironmentVars', jenkinsConfig.environment)
    handleConfig('Creds', jenkinsConfig.credentials)
    handleConfig('Security', jenkinsConfig.security)
    handleConfig('Notifiers', jenkinsConfig.notifiers)
    handleConfig('ScriptApproval', jenkinsConfig.script_approval)
    handleConfig('Gitlab', jenkinsConfig.gitlab)
    handleConfig('PipelineLibraries', jenkinsConfig.pipeline_libraries)
    handleConfig('JobsFolder', jenkinsConfig.jobs_folder)
    handleConfig('SeedJobs', jenkinsConfig.seed_jobs)
}