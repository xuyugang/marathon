Still WIP

## Requires

* docker (tested with 17.06.1-ce)
* docker-compose (tested with 1.14 on Mac)
* Ammonite (tested with 1.0.2)

## working logstash conf requires:

create a single file or a folder of files with the following:

* input statement for each file we are loading (input-for-loading.conf)
* parse outer metadata (timestamp, multi-line)
* parse messages (including log level?)
* write to ES (declarative)

*notes:*

* may want sc script to output files
* need for ammonite script
  * need IP address
  * potentially unzip files
  * need each file
