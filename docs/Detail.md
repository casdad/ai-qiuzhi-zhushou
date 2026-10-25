# 开发环境配置

本项目的完整安装步骤见 [README](../README.md)。本文补充 IDE 相关的配置细节。

## JDK 21

构建脚本已声明 Java 21 工具链：

```kotlin
java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(21)) }
}
```

下载 Temurin 21：https://adoptium.net/temurin/releases/?version=21

无需配置系统环境变量，在 IDE 中指定安装路径即可。`run.py` 会自动探测本机的 JDK 21，找不到时再回退到 `JAVA_HOME`。

## 在 IntelliJ IDEA 中配置 JDK

1. 打开 IDEA，进入 `File` → `Project Structure`。
2. 选择 `Project` 节点。
3. 在 `SDK` 下拉框中选择已安装的 JDK 21；没有则点击 `Add SDK` → `JDK`，指向安装目录。
4. 在 `Language level` 中选择 `21`。
5. 点击 `Apply` → `OK`。

JDK 安装目录应包含 `bin`、`lib` 等子目录以及 `release` 文件。

## 构建工具：Gradle

项目使用 Gradle 包装器，**无需单独安装**。仓库已包含 `gradlew`（Windows 为 `gradlew.bat`），首次执行时会自动下载对应版本。

在 IDEA 中打开项目时，选择 `Use Gradle Wrapper` 导入即可，随后 IDEA 会自动同步依赖。

## 前端依赖

只有修改前端源码时才需要：

```bash
cd front
corepack enable
pnpm install
```

> 注意不要使用 `pnpm run build:prod`：pnpm 在执行脚本前会做依赖检查，
> 而本项目 `sharp` / `unrs-resolver` 的构建脚本被 pnpm 默认忽略，
> 检查会以退出码 1 结束并挡住真正的构建。Gradle 构建脚本内部是直接用
> `node` 调用 `next` 来规避这个问题的。

## 准备数据库

项目不会自动建表，需要把 release 中的 `getjobs.db.template` 重命名为
`getjobs.db` 并放到 `db/` 目录下，否则启动时会直接失败。

## 运行

推荐直接运行根目录的 `run.py`，它会完成工作目录修正、数据库检查与 JDK 选择。

若要单独调试后端，运行启动类
[GetJobsApplication.java](../src/main/java/com/getjobs/GetJobsApplication.java)。
