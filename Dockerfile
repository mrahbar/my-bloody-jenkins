FROM jenkins/jenkins:2.107-alpine
MAINTAINER Mahmoud Azad <mrahbar.azad@gmail.com>

ARG JENKINS_TIMEZONE="Europe/Berlin"
ENV JENKINS_WORKSPACE_HOME="/jenkins-workspace-home"
ENV JENKINS_PERSISTANT_STATE="/jenkins-persistant-state"
ARG GOSU_VERSION=1.10

# Using root to install and run entrypoint.
# We will change the user to jenkins using gosu
USER root

# Ability to use usermod + install awscli in order to be able to watch s3 if needed
# https://wiki.alpinelinux.org/wiki/Docker
RUN echo "http://dl-cdn.alpinelinux.org/alpine/edge/community" > /etc/apk/repositories \
    && echo "http://dl-cdn.alpinelinux.org/alpine/edge/main" >> /etc/apk/repositories \
    && apk add --update --no-cache docker python python-dev procps ncurses shadow py-setuptools less outils-md5 \
    && easy_install-2.7 pip \
    && pip install awscli

## Use this to be able to watch s3 configuration file and update jenkins everytime it changes
RUN curl  -SsLo /usr/bin/watch-file.sh https://raw.githubusercontent.com/odavid/utility-scripts/master/scripts/watch-file.sh && \
    chmod +x /usr/bin/watch-file.sh

RUN curl -SsLo /usr/bin/gosu https://github.com/tianon/gosu/releases/download/${GOSU_VERSION}/gosu-amd64 && \
     chmod +x /usr/bin/gosu

COPY update-config.sh /usr/bin/
RUN chmod +x /usr/bin/update-config.sh

# Separate between JENKINS_HOME and WORKSPACE dir. Best if we use NFS for JENKINS_HOME
RUN mkdir -p ${JENKINS_WORKSPACE_HOME} && \
    chown -R jenkins:jenkins ${JENKINS_WORKSPACE_HOME}

RUN mkdir -p ${JENKINS_PERSISTANT_STATE} && \
    chown -R jenkins:jenkins ${JENKINS_PERSISTANT_STATE}

# Change the original entrypoint. We will later on run it using gosu
RUN mv /usr/local/bin/jenkins.sh /usr/local/bin/jenkins-orig.sh
COPY jenkins.sh /usr/local/bin/jenkins.sh
RUN chmod +x /usr/local/bin/jenkins.sh

# installing specific list of plugins. see: https://github.com/jenkinsci/docker/blob/master/README.md#preinstalling-plugins
COPY plugins.txt /usr/share/jenkins/ref/
COPY install-plugins-with-retry.sh /usr/local/bin/install-plugins-with-retry.sh
RUN chmod +x /usr/local/bin/install-plugins-with-retry.sh
RUN /usr/local/bin/install-plugins-with-retry.sh < /usr/share/jenkins/ref/plugins.txt

# Add all init groovy scripts to ref folder and change their ext to .override
# so Jenkins will override them every time it starts
COPY init-scripts/* /usr/share/jenkins/ref/init.groovy.d/

# Add configuration handlers groovy scripts
COPY config-handlers /usr/share/jenkins/config-handlers

ENV JOB_PIPELINE_LOCATION=/tmp/jobs
COPY config/jobs ${JOB_PIPELINE_LOCATION}

RUN cd /usr/share/jenkins/ref/init.groovy.d/ && \
    for f in *.groovy; do mv "$f" "${f}.override"; done

VOLUME /var/jenkins_home
VOLUME ${JENKINS_WORKSPACE_HOME}
VOLUME ${JENKINS_PERSISTANT_STATE}

RUN mkdir -p /usr/share/jenkins/ref/secrets
RUN echo "false" > /usr/share/jenkins/ref/secrets/slave-to-master-security-kill-switch

# Revert to root
USER root
RUN mkdir -p /dev/shm
ENV CONFIG_FILE_LOCATION=/dev/shm/jenkins-config.yml
ENV TOKEN_FILE_LOCATION=/dev/shm/.api-token
ENV CONFIG_CACHE_DIR=/dev/shm/.jenkins-config-cache
ENV QUIET_STARTUP_FILE_LOCATION=/dev/shm/quiet-startup-mutex

####################################################################################
# GENERAL Configuration variables
####################################################################################
ENV JENKINS_OPTS --httpPort=8080
ENV JENKINS_ENV_EXECUTERS=4
# See https://jenkins.io/blog/2017/04/11/new-cli/
ENV JENKINS_ENV_CLI_REMOTING_ENABLED=false
# See https://wiki.jenkins.io/display/JENKINS/CSRF+Protection
ENV JENKINS_ENV_CSRF_PROTECTION_ENABLED=true
# Agent JNLP Protocol
ENV JENKINS_ENV_JNLP_KILL_SWITCH=true
# If true, then workspaceDir will changed its defaults from ${JENKINS_HOME}/workspace
# to /jenkins-workspace-home/workspace/${ITEM_FULLNAME}
# This is useful in case your JENKINS_HOME is mapped to NFS mount,
# slowing down the workspace
ENV JENKINS_ENV_CHANGE_WORKSPACE_DIR=true
# If true, every DSL script would have to be approved using the ScriptApproval console
# See https://github.com/jenkinsci/job-dsl-plugin/wiki/Script-Security
ENV JENKINS_ENV_USE_SCRIPT_SECURITY=false
####################################################################################
# ADDITIONAL JAVA_OPTS
####################################################################################
# Each JAVA_OPTS_* variable will be added to the JAVA_OPTS variable before startup
#
# Enable permissive script security
ENV JAVA_OPTS_PERMISSIVE_SCRIPT_SECURITY="-Dpermissive-script-security.enabled=true"
# Set Timezone
ENV JAVA_OPTS_TIMEZONE="-Duser.timezone=${JENKINS_TIMEZONE}"
# Don't run the setup wizard
ENV JAVA_OPTS_DISABLE_WIZARD="-Djenkins.install.runSetupWizard=false"
# See https://wiki.jenkins.io/display/JENKINS/Configuring+Content+Security+Policy
ENV JAVA_OPTS_CSP="-Dhudson.model.DirectoryBrowserSupport.CSP=\"sandbox allow-same-origin allow-scripts; default-src 'self'; script-src * 'unsafe-eval'; img-src *; style-src * 'unsafe-inline'; font-src *\""
# See https://issues.jenkins-ci.org/browse/JENKINS-24752
ENV JAVA_OPTS_LOAD_STATS_CLOCK="-Dhudson.model.LoadStatistics.clock=1000"
####################################################################################

####################################################################################
# JNLP Tunnel Variables
####################################################################################
# Default port for http
ENV JENKINS_HTTP_PORT_FOR_SLAVES=9090
# This is used by docker slaves to get the actual jenkins URL
# in case jenkins is behind a load-balancer or a reverse proxy
#
# JENKINS_IP_FOR_SLAVES will be evaluated in the following order:
#    $JENKINS_ENV_HOST_IP ||
#    $(eval $JENKINS_ENV_HOST_IP_CMD) ||
#    ''
#ENV JENKINS_ENV_HOST_IP=<REAL_IP>
#ENV JENKINS_ENV_HOST_IP_CMD='<command to fetch ip>'
# This variable will be evaluated and should retrun a valid IP address:
# AWS:      JENKINS_ENV_HOST_IP_CMD='curl http://169.254.169.254/latest/meta-data/local-ipv4'
# General:  JENKINS_ENV_HOST_IP_CMD='ip route | grep default | awk '"'"'{print $3}'"'"''
####################################################################################

# If sshd enabled, this will be the port
EXPOSE 16022
