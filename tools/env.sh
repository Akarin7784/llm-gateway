export JAVA_HOME='/c/Program Files/Microsoft/jdk-21.0.12.101-hotspot'
export MAVEN_HOME='/d/AgentWorkspace/Qcoder/面试项目三号/tools/apache-maven-3.9.16'
export PATH="$JAVA_HOME/bin:$MAVEN_HOME/bin:$PATH"
# The Windows console codepage mangles the compiler's Chinese paths otherwise.
export MAVEN_OPTS="-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -Dfile.encoding=UTF-8"
