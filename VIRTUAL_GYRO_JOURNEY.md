# Virtual Gyroscope qua WiFi — Nhật ký dự án

Làm gyroscope ảo cho điện thoại Samsung SM-A055F (chip MediaTek MT6768,
không có gyro phần cứng), để **mọi app/game** đều nhận được như 1 sensor
thật qua `SensorManager` — không chỉ app tự viết. Toàn bộ làm trên điện
thoại qua Termux, không có PC, build nặng đẩy qua GitHub Actions.

Repo: `loc4135rt46-code/virtual_gyro`

## Kết quả cuối

App **Gyroscope Test** báo `Gyroscope: Yes`, chi tiết `Vendor: VirtualGyro`,
`Model: Virtual Gyro Sensor`. Xoay máy (thật ra là xoay ESP8266) thì số
liệu chạy theo thật, game nhận được luôn.

## Kiến trúc cuối cùng

```
ESP8266 (NodeMCU, đọc MPU6050) --UDP broadcast--> app Android (GyroUdpService)
                                                        |
                                          TCP loopback 127.0.0.1:8842
                                                        v
                                    gyro_relay (daemon C, chạy root)
                                                        |
                                    Unix abstract socket @virtgyro
                                                        v
                              VirtualGyroSubHal.so (nạp qua samsung-multihal)
                                                        |
                                                  SensorManager
                                          (mọi app/game đều thấy)
```

Root bằng **KernelSU** (không dùng LSPosed/Xposed để tránh lộ root).

---

## Timeline

### 1. Thiết kế ban đầu: ESP32-C3 + BLE
- Board ESP32-C3 SuperMini + MPU6050 qua I2C, gửi gyro qua BLE notify.
- App Android làm BLE central, forward qua TCP loopback tới `gyro_relay`
  (daemon root), ghi vào `/dev/socket/virtgyro` cho HAL đọc.
- Xác định máy dùng `samsung-multihal`, đọc `hals.conf`, chuẩn
  AOSP `ISensorsSubHal` 2.1 — xác nhận qua `nm -D` symbol dump của subhal
  mediatek thật.
- Vướng: compile BLE trên ArduinoDroid crash vì thiếu RAM.

### 2. ESP32-C3 hỏng → chuyển ESP8266, BLE → WiFi UDP
- ESP32-C3 hư, đổi sang **NodeMCU ESP8266** (không có BLE).
- Thiết kế lại: ESP8266 dùng **WiFiManager** (captive portal) để vào wifi
  nhà, sau đó gửi liên tục gyro qua **UDP broadcast** cổng cố định
  (47819) — app chỉ cần lắng nghe cổng đó, khỏi cần biết IP của ESP8266.
- Chân I2C giữ SDA=GPIO4(D2)/SCL=GPIO5(D1), tránh GPIO8/9 (chân strapping).
- Xác nhận ESP8266 gửi data thật ổn định qua `nc -ul 47819 | xxd`.

### 3. Repo bị reset — viết lại app + relay
- Repo GitHub bị mất gần hết (chỉ còn khung Gradle), phải viết lại:
  - **App Android** (`com.virtualgyro.client`): `GyroUdpService.kt`
    (foreground service, nghe UDP 47819, forward TCP loopback tới
    `gyro_relay`), thay `GyroBleClient.kt` cũ.
  - **`gyro_relay.c`**: daemon root, nghe TCP `127.0.0.1:8842`, forward
    vào Unix socket cho HAL.
- Build app qua GitHub Actions (`build-app.yml`) — vướng gói SDK `tools`
  đã bị Google gỡ khỏi kho, và thiếu `android.useAndroidX=true` trong
  `gradle.properties`.
- `gyro_relay.c` compile thẳng trên Termux bằng `clang` (không `-static`,
  vì Termux không có `libc.a`).

### 4. Viết `VirtualGyroSubHal` (theo đúng code mẫu AOSP thật)
- Cần header sinh bởi `hidl-gen` (`gen-hidl-headers.yml`, sparse-checkout
  `hardware/interfaces` + `system/libhidl` + `prebuilts/build-tools`).
- Artifact hidl-gen còn kèm sẵn code mẫu thật của AOSP
  (`fake_subhal/Sensor.h/.cpp`, `SensorsSubHal.h/.cpp`,
  `IHalProxyCallbackWrapper.h`) — dùng làm nền, rút gọn cho 1 sensor
  gyro duy nhất, thay data giả bằng đọc thật từ socket.
- Cần thêm header `libutils`/`libcutils` (`system/core`) và `liblog`
  (`system/logging`, đã tách khỏi `system/core`).
- `build-subhal.yml`: build bằng NDK clang, API 30.

### 5. Cuộc chiến với module KernelSU
Loạt lỗi liên tiếp, mỗi lỗi tốn 1-2 vòng flash-reboot-debug:

| Vấn đề | Nguyên nhân | Cách sửa |
|---|---|---|
| Gói SDK `tools` lỗi | Google đã gỡ khỏi kho | Chỉ định `packages: platform-tools` |
| `checkDebugAarMetadata` FAILED | Thiếu `android.useAndroidX=true` | Thêm `gradle.properties` |
| Build lẫn code cũ | Repo cũ còn sót `com.example.virtualgyro` | Xoá thư mục gói cũ |
| `-lc` not found lúc compile | Termux không có `libc.a` tĩnh | Bỏ `-static` |
| `.so`/`hals.conf` không lên `/vendor` | KernelSU dùng metamodule `meta-overlayfs`, chỉ mount nội dung **tĩnh** có sẵn lúc flash, bỏ qua file `post-fs-data.sh` tự tạo lúc boot | `.so` đóng tĩnh trong zip ở đúng cấp `vendor/lib64/hw/`; `hals.conf` sửa bằng `mount -o bind` trực tiếp trong `post-fs-data.sh`, không cần metamodule |
| Bind-mount `.so` làm mất data 2 file sensor thật | `mount -o bind` (không đệ quy) đè thư mục không mang theo bind-mount lẻ bên trong | Đổi sang `mount -o **rbind**` (đệ quy) |
| Symbol `__ndk1` | Header libc++ NDK khác namespace với `libc++.so` hệ thống (`std::__1`) | Copy header, sed đổi `__ndk1` → `__1`; chỉ NEEDED `libc++.so` thật, không link libc++ NDK |
| Undefined typeinfo `IBase`/`ISensors` | Lib HIDL trên máy build `-fno-rtti`, không xuất typeinfo | Thêm `-fno-rtti` |
| `dlopen failed` (không rõ lý do) | Samsung binary không in `dlerror()` | Tự viết `dlopen_test.c`, chạy **từ `/vendor/lib64/hw/`** (đúng linker namespace vendor) để lấy lỗi thật |
| Thiếu symbol `ISensors::interfaceChain` | Các hàm "mặc định" kiểu `IBase` (`interfaceChain`, `ping`...) nằm thật trong `android.hardware.sensors@{1.0,2.0,2.1}.so` của máy — code chỉ compile theo header, chưa **link** vào 3 file đó | Pull 3 file `.so` thật từ `/vendor/lib64/`, link thẳng vào (`prebuilt-libs/` trong repo) |
| Thiếu tiếp `ScopedWakelock::ScopedWakelock(&&)` | Nằm ở 1 file `.so` riêng | Pull thêm `android.hardware.sensors@2.0-ScopedWakelock.so` |

Ngoài ra còn loại trừ nhiều hướng sai: không phải mount namespace riêng
của HAL (`diag2.sh` xác nhận thấy đúng), không phải giới hạn số lượng
subhal (thử thay hẳn `Grip_HAL` bằng gyro, vẫn không lên), không phải
đường dẫn `/odm/etc/sensors/hals.conf` (không tồn tại trên máy này),
không phải SELinux (đang permissive sẵn), không crash/tombstone.

### 6. Thành công
- `dlopen_test_tool` báo `dlopen THANH CONG` + `dlsym` ra địa chỉ hợp lệ.
- Đóng gói module `virtgyro` bản **v1.7**, flash, reboot.
- `dumpsys android.hardware.sensors.ISensors/default` → `SubHals (3)`.
- App **Gyroscope Test**: `Gyroscope: Yes`, xoay ESP8266 thì số chạy
  theo thật. Game nhận được gyro bình thường.

---

## Cấu trúc các file chính

- `EspGyro8266.ino` — firmware ESP8266 (WiFiManager + UDP broadcast)
- `app/` — Android app (`GyroUdpService.kt`, `MainActivity.kt`)
- `gyro_relay.c` — daemon root, TCP loopback → Unix abstract socket `@virtgyro`
- `subhal/` — `Sensor.h/.cpp`, `VirtualGyroSubHal.h/.cpp`,
  `IHalProxyCallbackWrapper.h`, `export.map`
- `prebuilt-libs/` — 4 file `.so` thật pull từ máy (bắt buộc phải có để
  link: `android.hardware.sensors@1.0.so`, `@2.0.so`, `@2.1.so`,
  `@2.0-ScopedWakelock.so`)
- `.github/workflows/`
  - `build-app.yml` — build APK
  - `gen-hidl-headers.yml` — sinh header HIDL + lấy code mẫu multihal
  - `build-subhal.yml` — build `VirtualGyroSubHal.so`
- Module KernelSU (`virtgyro-module.zip`, v1.7):
  - `module.prop`, `service.sh` (chạy `gyro_relay`)
  - `post-fs-data.sh` (bind-mount `hals.conf` + rbind `.so` vào
    `/vendor/lib64/hw`, tự làm hết, không phụ thuộc metamodule)
  - `gyro_relay`, `vendor/lib64/hw/android.hardware.sensors@2.X-subhal-virtgyro.so`

## Bài học chính

1. **Không có PC vẫn làm được HAL-level mod** — miễn kiên nhẫn, dùng
   đúng tổ hợp Termux (build nhỏ, test nhanh) + GitHub Actions (build
   nặng: NDK, hidl-gen).
2. **`dlopen` thất bại thường im lặng trên các ROM custom** — tự viết
   tool nhỏ gọi thẳng `dlopen()`/`dlsym()` để lấy `dlerror()` thật, chạy
   đúng từ thư mục đích để có đúng linker namespace, hiệu quả hơn nhiều
   so với đoán qua log.
3. **Không phải mọi symbol "thiếu" đều là lỗi** — với `.so` phụ thuộc
   `.so` khác, symbol từ thư viện ngoài luôn hiện "undefined" trong
   chính file của mình, chỉ nối thật lúc `dlopen` trên máy.
4. **`mount -o bind` vs `mount -o rbind`** — đè cả thư mục lên thư mục
   khác thì bắt buộc dùng `rbind` nếu bên trong có bind-mount lồng nhau,
   không thì mất nội dung file gốc.
5. **Metamodule (`meta-overlayfs`) không tự nhận file sinh ra lúc boot**
   — chỉ mount nội dung tĩnh có sẵn lúc flash; muốn nội dung động phải
   tự bind-mount lấy trong `post-fs-data.sh`.
