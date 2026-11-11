#!/bin/bash

# Set environment variables
export ECS_S3_ACCESS_KEY="object_admin1"
export ECS_S3_SECRET_KEY="ChangeMeChangeMeChangeMeChangeMeChangeMe"
export ECS_S3_ENDPOINT="http://192.168.31.200:9020"
export ECS_S3_BUCKET="test1"
export ECS_S3_ACCESS_TARGET_USER="object_user1"

# Build and run with Maven
mvn clean package

echo ""
echo "=== Running ECS S3 Permission Manager ==="
java -jar target/ecs-s3-permission-1.0-SNAPSHOT-jar-with-dependencies.jar

echo ""
echo "=== Current Permissions for object_user1 ==="
echo "Bucket: test1"
echo "Permissions granted: READ, READ_ACP"
