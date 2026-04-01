#!/bin/sh
# ----------------------------------------------------------------------------
# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#    https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.
# ----------------------------------------------------------------------------

# ----------------------------------------------------------------------------
# Apache Maven Wrapper startup batch script, version 3.3.2
#
# Environment Variable Prerequisites:
#
#   JAVA_HOME       Must point at your Java Development Kit installation.
#   MAVEN_OPTS      (Optional) Java runtime options used when Maven is executed.
#   MAVEN_SKIP_RC   (Optional) Flag to disable loading of mavenrc files.
# ----------------------------------------------------------------------------

if [ -z "$MAVEN_SKIP_RC" ]; then

    if [ -f /usr/local/etc/mavenrc ]; then
        . /usr/local/etc/mavenrc
    fi

    if [ -f /etc/mavenrc ]; then
        . /etc/mavenrc
    fi

    if [ -f "$HOME/.mavenrc" ]; then
        . "$HOME/.mavenrc"
    fi

fi

# OS specific support. $var _must_ be set to either true or false.
cygwin=false
darwin=false
mingw=false
case "$(uname)" in
    CYGWIN*) cygwin=true ;;
    MINGW*) mingw=true ;;
    Darwin*) darwin=true
        # Use /usr/libexec/java_home if available, otherwise fall back
        # to /Library/Java/Home with a link to /usr/bin/java
        if [ -z "$JAVA_HOME" ]; then
            if [ -x "/usr/libexec/java_home" ]; then
                JAVA_HOME="$(/usr/libexec/java_home)"; export JAVA_HOME
            else
                JAVA_HOME="/Library/Java/Home"; export JAVA_HOME
            fi
        fi
        ;;
esac

if [ -z "$JAVA_HOME" ]; then
    javaExecutable="$(which javac)"
    if [ -n "$javaExecutable" ] && ! [ "$(expr "\"$javaExecutable\"" : '\([^ ]*\)')" = "no" ]; then
        # readlink(1) is not available as standard on Solaris 10.
        readLink=$(which readlink)
        if [ ! "$(expr "$readLink" : '\([^ ]*\)')" = "no" ]; then
            if $darwin; then
                javaHome="$(dirname "\"$javaExecutable\"")"
                javaHome=$(cd "$(dirname "\"$javaHome\"")" && pwd)
            else
                javaExecutable="$(readlink -f "\"$javaExecutable\"")"
                javaHome="$(dirname "\"$javaExecutable\"")"
                javaHome=$(cd "$(dirname "\"$javaHome\"")" && pwd)
            fi
            JAVA_HOME="$javaHome"
            export JAVA_HOME
        fi
    fi
fi

if [ -z "$JAVACMD" ]; then
    if [ -n "$JAVA_HOME" ]; then
        if [ -x "$JAVA_HOME/jre/sh/java" ]; then
            # IBM's JDK on AIX uses strange locations for the executables
            JAVACMD="$JAVA_HOME/jre/sh/java"
        else
            JAVACMD="$JAVA_HOME/bin/java"
        fi
    else
        JAVACMD="$(\unset -f command 2>/dev/null; \command -v java)" || :
    fi
fi

if [ ! -x "$JAVACMD" ]; then
    echo "Error: JAVA_HOME is not defined correctly." >&2
    echo "  We cannot execute $JAVACMD" >&2
    echo "  Please set the JAVA_HOME variable in your environment to match the" >&2
    echo "  location of your Java installation." >&2
    exit 1
fi

if [ -z "$JAVA_HOME" ]; then
    echo "Warning: JAVA_HOME environment variable is not set." >&2
fi

# traverses directory structure from process work directory to filesystem root
# first directory with .mvn subdirectory is considered project base directory
find_maven_basedir() {
    if [ -z "$1" ]; then
        echo "Path not specified to find_maven_basedir" >&2
        return 1
    fi

    basedir="$1"
    wdir="$1"
    while [ "$wdir" != '/' ]; do
        if [ -d "$wdir"/.mvn ]; then
            basedir=$wdir
            break
        fi
        # workaround for JBEAP-8937 (on Solaris 10/Sparc)
        if [ -d "${wdir}" ]; then
            wdir=$(cd "$wdir/.." && pwd)
        fi
        # end of workaround
    done
    printf '%s' "$(cd "$basedir" && pwd -W 2>/dev/null || pwd)"
}

# concatenates all lines of a file
concat_lines() {
    if [ -f "$1" ]; then
        # Remove comments and blank lines
        grep -v '^#' "$1" | tr -s '\n' ' ' | tr -d '\r'
    fi
}

MAVEN_PROJECTBASEDIR="${MAVEN_BASEDIR:-$(find_maven_basedir "$(dirname "$0")")}"
MAVEN_OPTS="$(concat_lines "$MAVEN_PROJECTBASEDIR/.mvn/jvm.config") $MAVEN_OPTS"

# For Cygwin, switch paths to Windows format before running java
if $cygwin; then
    [ -n "$JAVA_HOME" ] &&
        JAVA_HOME=$(cygpath --path --windows "$JAVA_HOME")
    [ -n "$CLASSPATH" ] &&
        CLASSPATH=$(cygpath --path --windows "$CLASSPATH")
    [ -n "$MAVEN_PROJECTBASEDIR" ] &&
        MAVEN_PROJECTBASEDIR=$(cygpath --path --windows "$MAVEN_PROJECTBASEDIR")
fi

# Provide a "standardized" way to retrieve the CLI args that will
# work with both Windows and non-Windows executions.
MAVEN_CMD_LINE_ARGS="$MAVEN_CONFIG $*"
export MAVEN_CMD_LINE_ARGS

WRAPPER_LAUNCHER=org.apache.maven.wrapper.MavenWrapperMain

# Download maven-wrapper.jar if not present
WRAPPER_JAR="$MAVEN_PROJECTBASEDIR/.mvn/wrapper/maven-wrapper.jar"
if [ ! -f "$WRAPPER_JAR" ]; then
    WRAPPER_URL="https://repo.maven.apache.org/maven2/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
    if [ -n "$MVNW_REPOURL" ]; then
        WRAPPER_URL="$MVNW_REPOURL/org/apache/maven/wrapper/maven-wrapper/3.3.2/maven-wrapper-3.3.2.jar"
    fi
    echo "Downloading Maven Wrapper from $WRAPPER_URL"
    if command -v wget > /dev/null 2>&1; then
        wget -q -O "$WRAPPER_JAR" "$WRAPPER_URL" || :
    elif command -v curl > /dev/null 2>&1; then
        curl -fsSL -o "$WRAPPER_JAR" "$WRAPPER_URL" || :
    else
        echo "Error: could not download maven-wrapper.jar. Neither wget nor curl found." >&2
    fi
fi

exec "$JAVACMD" \
    $MAVEN_OPTS \
    $MAVEN_DEBUG_OPTS \
    -classpath "$WRAPPER_JAR" \
    "-Dmaven.multiModuleProjectDirectory=$MAVEN_PROJECTBASEDIR" \
    $WRAPPER_LAUNCHER $MAVEN_CONFIG "$@"
