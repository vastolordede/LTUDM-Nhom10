@echo off
if not exist testdata mkdir testdata
fsutil file createnew testdata\small.bin 1048576
fsutil file createnew testdata\medium.bin 536870912
fsutil file createnew testdata\large3gb.bin 3221225472
echo Done.