@echo off
echo Installing dependencies...
call npm install
echo Copying Broadway decoder files...
if not exist "public\Decoder.js" copy "node_modules\broadway-player\Player\Decoder.js" "public\Decoder.js"

echo Copying TinyH264 files...
if not exist "public\tinyh264" mkdir "public\tinyh264"
copy /Y "node_modules\tinyh264\lib\TinyH264.js" "public\tinyh264\TinyH264.js"
copy /Y "node_modules\tinyh264\lib\TinyH264Worker.js" "public\tinyh264\TinyH264Worker.js"
copy /Y "node_modules\tinyh264\lib\TinyH264Decoder.js" "public\tinyh264\TinyH264Decoder.js"

echo Setup complete!
pause
