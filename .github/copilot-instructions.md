# ECS S3 Permission Manager

## 功能说明
该Java程序用于对ECS S3 bucket及其下的所有文件授予用户只读权限。

## 环境变量配置
在运行程序前，请设置以下环境变量：

```bash
export ECS_S3_ACCESS_KEY="object_admin1"
export ECS_S3_SECRET_KEY="ChangeMeChangeMeChangeMeChangeMeChangeMe"
export ECS_S3_ENDPOINT="http://192.168.31.200:9020"
export ECS_S3_BUCKET="test1"
```

## 编译项目

```bash
mvn clean package
```

## 运行程序

```bash
# 设置环境变量后运行
ECS_S3_ACCESS_KEY="object_admin1" \
ECS_S3_SECRET_KEY="ChangeMeChangeMeChangeMeChangeMeChangeMe" \
ECS_S3_ENDPOINT="http://192.168.31.200:9020" \
ECS_S3_BUCKET="test1" \
java -jar target/ecs-s3-permission-1.0-SNAPSHOT-jar-with-dependencies.jar
```

## 功能特性
1. 授予bucket级别的只读权限给目标用户 `object_user1`
2. 遍历bucket下所有对象并授予只读权限
3. 自动处理分页，支持大量对象
4. 检查是否已授权，避免重复操作
5. 详细的执行日志输出

## 技术栈
- Java 8
- AWS SDK for Java 2.x
- Maven
