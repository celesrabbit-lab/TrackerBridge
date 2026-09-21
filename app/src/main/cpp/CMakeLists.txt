cmake_minimum_required(VERSION 3.22.1)
project(trackerbridge_iso C)

add_library(trackerbridge_iso SHARED iso_usb.c raw_usb.c)
target_compile_options(trackerbridge_iso PRIVATE -Wall -O2)
# Paginas de 16 KB: compatible con los dispositivos Android que las usan
target_link_options(trackerbridge_iso PRIVATE "-Wl,-z,max-page-size=16384")
