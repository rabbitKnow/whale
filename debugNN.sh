export HADOOP_NAMENODE_OPTS="-Xdebug -Xrunjdwp:transport=dt_socket,address=8788,server=y,suspend=y"
rm -r ../HadoopRun
bin/start-dfs.sh

