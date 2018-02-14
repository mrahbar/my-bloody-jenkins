#! /bin/bash -e

if [[ $# -lt 1 ]] || [[ "$1" == "-"* ]]; then
    JAVA_OPTS_VARIABLES=$(compgen -v | while read line; do echo $line | grep JAVA_OPTS_;done) || true
    for key in $JAVA_OPTS_VARIABLES; do
        echo "adding: ${key} to JAVA_OPTS"
        export JAVA_OPTS="$JAVA_OPTS ${!key}"
    done

    if [ -n "${JENKINS_ENV_CONFIG_YAML}" ]; then
        echo -n "$JENKINS_ENV_CONFIG_YAML" > $CONFIG_FILE_LOCATION
        unset JENKINS_ENV_CONFIG_YAML
    elif [ -n "${JENKINS_ENV_CONFIG_YML_URL}" ]; then
        echo "Fetching config from URL: ${JENKINS_ENV_CONFIG_YML_URL}"
        watch-file.sh \
             --cache-dir $CONFIG_CACHE_DIR \
             --url "${JENKINS_ENV_CONFIG_YML_URL}" \
            --filename $CONFIG_FILE_LOCATION \
            --skip-watch
        if [ "$JENKINS_ENV_CONFIG_YML_URL_DISABLE_WATCH" != 'true' ]; then
            echo "Watching config from URL: ${JENKINS_ENV_CONFIG_YML_URL} in the backgroud"
            nohup watch-file.sh \
                --cache-dir $CONFIG_CACHE_DIR \
                --url "${JENKINS_ENV_CONFIG_YML_URL}" \
                --filename $CONFIG_FILE_LOCATION \
                --polling-interval "${JENKINS_ENV_CONFIG_YML_URL_POLLING:-30}" \
                --command 'update-config.sh' &
        fi
        unset AWS_ACCESS_KEY_ID
        unset AWS_SECRET_ACCESS_KEY
    fi

    if [ -n "$JENKINS_ENV_PLUGINS" ]; then
        echo "Installing additional plugins $JENKINS_ENV_PLUGINS"
        install-plugins-with-retry.sh $(echo $JENKINS_ENV_PLUGINS | tr ',' ' ')
        chown jenkins:jenkins /usr/share/jenkins/ref/
        echo "Installing additional plugins. Done..."
    fi

    # Because we are in docker, we need to fetch the real IP of jenkins, so ecs/kubernetes/docker cloud slaves will
    # be able to connect to it
    # If it is running with docker network=host, then the default ip address will be sufficient
    if [ -n "${JENKINS_ENV_HOST_IP}" ]; then
        export JENKINS_IP_FOR_SLAVES="${JENKINS_ENV_HOST_IP}"
        unset JENKINS_ENV_HOST_IP
    elif [ -n "${JENKINS_ENV_HOST_IP_CMD}" ]; then
        export JENKINS_IP_FOR_SLAVES="$(eval ${JENKINS_ENV_HOST_IP_CMD})" || true
        unset JENKINS_ENV_HOST_IP_CMD
    fi
    echo "JENKINS_IP_FOR_SLAVES = ${JENKINS_IP_FOR_SLAVES}"


    # This is important if you let docker create the host mounted volumes.
    # We need to make sure they will be owned by the jenkins user
    mkdir -p ${JENKINS_WORKSPACE_HOME}
    if [ "jenkins" != "$(stat -c %U ${JENKINS_WORKSPACE_HOME})" ]; then
        chown -R jenkins:jenkins ${JENKINS_WORKSPACE_HOME}
    fi
    mkdir -p ${JENKINS_PERSISTANT_STATE}
    if [ "jenkins" != "$(stat -c %U ${JENKINS_PERSISTANT_STATE})" ]; then
        chown -R jenkins:jenkins ${JENKINS_PERSISTANT_STATE}
    fi
    if [ "jenkins" != "$(stat -c %U ${JENKINS_HOME})" ]; then
        chown -R jenkins:jenkins $JENKINS_HOME
    fi

    # To enable docker cloud based on docker socket,
    # we need to add jenkins user to the docker group
    if [ "$DOCKER_BIND_SOCK" == 'true' ] && [ -S /var/run/docker.sock ]; then
        JENKINS_USER="jenkins"
        DOCKER_GROUP="docker"
        DOCKER_GID=$(stat -c %g /var/run/docker.sock)

        if getent group $DOCKER_GROUP; then
            EXISTING_DOCKER_GID=$(getent group $DOCKER_GROUP | awk -F: '{print $3}')
            if [[ "$EXISTING_DOCKER_GID" -ne "$DOCKER_GID" ]];then
              echo "Existing group $DOCKER_GROUP ($EXISTING_DOCKER_GID) has not gid of $DOCKER_GID. Recreating...";
              groupdel $DOCKER_GROUP
              addgroup -g $DOCKER_GID $DOCKER_GROUP
            fi
        else
            echo "Creating group $DOCKER_GROUP with gid $DOCKER_GID"
            addgroup --g $DOCKER_GID $DOCKER_GROUP
        fi

        echo "Adding user $JENKINS_USER to group $DOCKER_GROUP"
        adduser $JENKINS_USER $DOCKER_GROUP
    else
        echo "Skipping docker sock binding"
    fi

    # This changes the actual command to run the original jenkins entrypoint
    # using the jenkins user
    set -- gosu jenkins /usr/local/bin/jenkins-orig.sh "$@"
fi

exec "$@"