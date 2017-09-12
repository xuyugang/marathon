#!/bin/bash

# TODO: What about mac?
cat /proc/sys/net/ipv4/ip_local_port_range
sysctl -w net.ipv4.ip_local_port_range="60001 61000"
