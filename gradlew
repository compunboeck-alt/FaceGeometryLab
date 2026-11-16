#!/bin/sh
set -eu
project_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
java_exe=java
if [ -n "${JAVA_HOME:-}" ]; then
    java_exe="$JAVA_HOME/bin/java"
fi
exec "$java_exe" -classpath "$project_dir/gradle/wrapper/gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain "$@"
