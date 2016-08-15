echo "put run1/"
bin/hadoop fs -put README.txt  /run1/test/txt.txtant
echo "put run2/"
bin/hadoop fs -put README.txt  /run2/test/txt.txt
echo "put run3/"
bin/hadoop fs -put README.txt  /run3/test/txt3.txt
echo "put run4/"
bin/hadoop fs -put README.txt  /run4/test/txt4.txt
echo "put run5/"
bin/hadoop fs -put README.txt  /run5/test/txt4.txt
echo "put run6/"
bin/hadoop fs -put README.txt  /run6/test/txt4.txt
echo "put run7/"
bin/hadoop fs -put README.txt  /run7/test/txt4.txt
bin/hadoop fs -ls /
