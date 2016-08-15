bin/stop-all.sh
rm -r ../HadoopRun
rm -r ../metaDataDir/*
bin/hadoop namenode -format
rm -r logs/*
bin/start-all.sh
