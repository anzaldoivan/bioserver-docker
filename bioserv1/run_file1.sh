#!/bin/bash
set -e

if [ -e ./bioserver/patch.raw ]
    then 
        mv ./bioserver/patch.raw .
    else 
        echo 'patch.raw not found'
fi

java -cp "lib/*:bin" bioserver.ServerMain
