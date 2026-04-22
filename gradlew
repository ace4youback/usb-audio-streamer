#!/usr/bin/env sh
APP_HOME=`pwd -P`
CLASSPATH=$APP_HOME/gradle/wrapper/gradle-wrapper.jar
JAVACMD="java"
if [ -n "$JAVA_HOME" ] ; then
    JAVACMD="$JAVA_HOME/bin/java"
fi
exec "$JAVACMD" \
  -classpath "$CLASSPATH" \
  org.gradle.wrapper.GradleWrapperMain \
  "$@"
