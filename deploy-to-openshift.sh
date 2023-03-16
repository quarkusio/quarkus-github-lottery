#! /bin/bash

# login to the OpenShift cluster before launching this script

# switch to the right project
oc project prod-quarkus-github-lottery

# delete problematic image
oc delete is ubi-quarkus-native-binary-s2i

./mvnw clean package -Dquarkus.kubernetes.deploy=true -Dquarkus.native.container-build=true -Dnative -Drevision=$(git rev-parse HEAD)
