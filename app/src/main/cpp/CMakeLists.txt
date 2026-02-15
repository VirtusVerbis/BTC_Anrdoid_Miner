cmake_minimum_required(VERSION 3.13.1)
project("miner")

add_library(miner SHARED miner.c sha256.c)

# 16 KB page alignment for Android 15+ and 16 KB devices (Play requirement from Nov 2025)
target_link_options(miner PRIVATE "LINKER:-z,max-page-size=16384")
