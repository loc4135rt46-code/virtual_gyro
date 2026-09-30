# Virtual Gyro 🌀

Biến ESP8266 + MPU6050 thành **gyroscope thật** cho điện thoại Android
không có cảm biến gyro phần cứng — nhận diện qua `SensorManager` như
sensor gốc, **mọi app/game đều dùng được**, không chỉ app tự viết.

Build & test trên **Samsung SM-A055F** (chip MediaTek MT6768,
`samsung-multihal`), root bằng **KernelSU**.

[![build-app](https://github.com/loc4135rt46-code/virtual_gyro/actions/workflows/build-app.yml/badge.svg)](https://github.com/loc4135rt46-code/virtual_gyro/actions/workflows/build-app.yml)
[![build-subhal](https://github.com/loc4135rt46-code/virtual_gyro/actions/workflows/build-subhal.yml/badge.svg)](https://github.com/loc4135rt46-code/virtual_gyro/actions/workflows/build-subhal.yml)

## Vì sao

Máy tầm trung/giá rẻ nhiều khi thiếu hẳn gyroscope, khiến các app/game
cần xoay-nghiêng (FPS, đua xe, VR...) không chơi được. Virtual Gyro
gắn 1 con ESP8266 rẻ tiền + MPU6050 ra ngoài, gửi dữ liệu về máy qua
WiFi, rồi "tiêm" thẳng vào tầng HAL của Android để hệ thống tin là
gyro thật.

## Kiến trúc

```
ESP8266 (đọc MPU6050) --UDP broadcast--> app Android
                                               |
                                 TCP loopback 127.0.0.1:8842
                                               v
                                gyro_relay (daemon C, chạy root)
                                               |
                                 Unix abstract socket @virtgyro
                                               v
                          VirtualGyroSubHal.so (subhal HIDL 2.1)
                                               |
                                         SensorManager
                                   (mọi app/game đều thấy)
```

## Gồm những gì

| Thành phần | Việc gì | Thư mục |
|---|---|---|
| Firmware ESP8266 | Đọc MPU6050, cấu hình WiFi qua captive portal (WiFiManager), gửi gyro qua UDP broadcast | `EspGyro8266.ino` |
| App Android | Nghe UDP, forward vào relay | `app/` |
| `gyro_relay` | Daemon root, cầu nối TCP → Unix socket | `gyro_relay.c` |
| `VirtualGyroSubHal` | Sub-HAL HIDL 2.1, đăng ký gyro ảo với hệ thống | `subhal/` |
| Module KernelSU | Tự chạy relay + nạp subhal lúc boot | `module.prop`, `service.sh`, `post-fs-data.sh` |

## Yêu cầu

- Điện thoại Android **đã root bằng KernelSU**, dùng sensors HAL kiểu
  HIDL 2.1 multihal (kiểm tra: có file `/vendor/etc/sensors/hals.conf`)
- ESP8266 (NodeMCU/D1 mini...) + cảm biến MPU6050
- Cùng mạng WiFi giữa điện thoại và ESP8266

## Cài đặt

1. **Nạp firmware cho ESP8266**: mở `EspGyro8266.ino` bằng Arduino
   IDE/ArduinoDroid (cần lib `WiFiManager` của tzapu), nạp vào board.
   Lần đầu bật, ESP8266 mở AP `VirtualGyroSetup` — kết nối vào, chọn
   wifi nhà, nhập mật khẩu.
2. **Cài app Android**: tải APK từ tab
   [Actions → build-app](../../actions/workflows/build-app.yml)
   (artifact `virtualgyro-debug-apk`), cài và mở lên (chạy nền, nghe
   UDP cổng `47819`).
3. **Build subhal + module**: workflow
   [`build-subhal.yml`](../../actions/workflows/build-subhal.yml) ra
   `.so`; cần 4 file `.so` **thật lấy từ chính máy bạn** (không dùng
   chung được giữa các máy khác dòng chip) bỏ vào `prebuilt-libs/`:
   ```
   /vendor/lib64/android.hardware.sensors@1.0.so
   /vendor/lib64/android.hardware.sensors@2.0.so
   /vendor/lib64/android.hardware.sensors@2.1.so
   /vendor/lib64/android.hardware.sensors@2.0-ScopedWakelock.so
   ```
4. **Flash module**: đóng `gyro_relay` (build tay qua `clang` trong
   Termux) + `.so` vừa build vào 1 zip theo cấu trúc module KernelSU,
   flash qua KernelSU Manager, reboot.
5. Kiểm tra: bất kỳ app đo sensor nào (Sensor Box, Phyphox, Gyroscope
   Test...) sẽ thấy `Gyroscope: Yes`, vendor `VirtualGyro`.

## Lưu ý

- Chỉ test trên 1 máy cụ thể (Samsung SM-A055F, MT6768) — thiết bị
  khác có thể cần điều chỉnh đường dẫn `hals.conf`, tên subhal thật để
  dò theo, hoặc cách metamodule của KernelSU xử lý mount.
- 4 file `.so` trong `prebuilt-libs/` là **binary hệ thống của máy bạn**,
  không phải mã nguồn mở — chỉ dùng để build lại đúng máy đó, đừng
  đem gán cho máy khác.
- `post-fs-data.sh` tự bind-mount thẳng, không phụ thuộc metamodule —
  nhưng vẫn nên có sẵn cách gỡ nhanh nếu sensor thật bị ảnh hưởng:
  ```
  touch /data/adb/modules/virtgyro/disable
  ```

## Nhật ký phát triển

Toàn bộ quá trình mò mẫm — từ BLE sang WiFi, repo bị reset phải viết
lại, tới cuộc chiến debug `dlopen` — ghi ở
[`VIRTUAL_GYRO_JOURNEY.md`](./VIRTUAL_GYRO_JOURNEY.md).

## Giấy phép

Chưa chọn license — thêm sau nếu định public rộng.
