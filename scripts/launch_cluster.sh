#!/bin/bash

set +x -e -o pipefail

# Hardcode configuration to open DC/OS.
variant="open"
channel="testing/pull/1739"

CHANNEL_SANITIZED=$(echo $channel | tr '[/.,=]' '-')
THISJOB="marathon-root-si-tests-${variant}-${CHANNEL_SANITIZED}-${BUILD_NUMBER}"


if [ "$variant" == "open" ]; then
  TEMPLATE="https://s3.amazonaws.com/downloads.dcos.io/dcos/${channel}/cloudformation/single-master.cloudformation.json"
else
  TEMPLATE="https://s3.amazonaws.com/downloads.mesosphere.io/dcos-enterprise-aws-advanced/${channel}/${variant}/cloudformation/ee.single-master.cloudformation.json"
fi

echo "Workspace: ${WORKSPACE}"
echo "Using: ${TEMPLATE}"

apk --upgrade add gettext wget
wget 'https://downloads.dcos.io/dcos-test-utils/bin/linux/dcos-launch' && chmod +x dcos-launch


envsubst <<EOF > config.yaml
---
launch_config_version: 1
template_url: $TEMPLATE
deployment_name: ${THISJOB}
provider: aws
aws_region: eu-central-1
template_parameters:
    KeyName: default
    AdminLocation: 0.0.0.0/0
    PublicSlaveInstanceCount: 1
    SlaveInstanceCount: 5
EOF
./dcos-launch create
./dcos-launch wait

DCOS_URL=$(./dcos-launch describe | jq -r ".masters[0].public_ip")/
echo "${DCOS_URL}" > "$WORKSPACE/dcos_url.properties"
