// Sensor.h
// Rut gon tu tham khao AOSP that:
//   hardware/interfaces/sensors/common/default/2.X/multihal/tests/fake_subhal/Sensor.h
// (co trong sensors-hidl-gen-output.zip, workflow gen-hidl-headers.yml)
// Chi giu lai phan "ContinuousSensor" can cho 1 gyroscope duy nhat, bo
// OnChangeSensor/AccelSensor/... va phan ho tro data injection (khong dung).

#pragma once

#include <android/hardware/sensors/2.1/types.h>

#include <atomic>
#include <condition_variable>
#include <mutex>
#include <thread>
#include <vector>

namespace android {
namespace hardware {
namespace sensors {
namespace V2_1 {
namespace subhal {
namespace implementation {

using ::android::hardware::Return;
using ::android::hardware::sensors::V1_0::OperationMode;
using ::android::hardware::sensors::V1_0::Result;
using ::android::hardware::sensors::V1_0::SensorFlagBits;
using ::android::hardware::sensors::V1_0::SensorStatus;
using ::android::hardware::sensors::V2_1::Event;
using ::android::hardware::sensors::V2_1::SensorInfo;
using ::android::hardware::sensors::V2_1::SensorType;

class ISensorsEventCallback {
  public:
    virtual ~ISensorsEventCallback() {}
    virtual void postEvents(const std::vector<Event>& events, bool wakeup) = 0;
};

// Giong het vong lap chay nen cua AOSP Sensor gonc (thread rieng, danh thuc
// theo mSamplingPeriodNs), chi khac o readEvents() la pure virtual (chi co
// 1 sensor con nen khong can gia tri mac dinh).
class Sensor {
  public:
    Sensor(int32_t sensorHandle, ISensorsEventCallback* callback);
    virtual ~Sensor();

    const SensorInfo& getSensorInfo() const;
    void batch(int64_t samplingPeriodNs);
    virtual void activate(bool enable);
    Result flush();
    void setOperationMode(OperationMode mode);

  protected:
    void run();
    virtual std::vector<Event> readEvents() = 0;
    static void startThread(Sensor* sensor);
    bool isWakeUpSensor();
    static int64_t nowBootNanos();

    bool mIsEnabled;
    int64_t mSamplingPeriodNs;
    int64_t mLastSampleTimeNs;
    SensorInfo mSensorInfo;

    std::atomic_bool mStopThread;
    std::condition_variable mWaitCV;
    std::mutex mRunMutex;
    std::thread mRunThread;

    ISensorsEventCallback* mCallback;
    OperationMode mMode;
};

// Doc gyro that tu socket Unix /dev/socket/virtgyro (gyro_relay ghi vao qua
// sendto AF_UNIX SOCK_DGRAM). Mot thread rieng (khac thread run() cua Sensor)
// lien tuc nhan goi 12 byte va cache lai gia tri moi nhat; readEvents() chi
// tra ve cache do theo dung nhip samplingPeriodNs ma framework yeu cau.
class VirtualGyroSensor : public Sensor {
  public:
    VirtualGyroSensor(int32_t sensorHandle, ISensorsEventCallback* callback);
    ~VirtualGyroSensor() override;

  protected:
    std::vector<Event> readEvents() override;

  private:
    void socketReaderLoop();

    std::atomic<int> mSocketFd;
    std::thread mSocketThread;
    std::atomic_bool mStopSocketThread;

    std::mutex mSampleMutex;
    float mLastX;
    float mLastY;
    float mLastZ;
    bool mHasSample;
};

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_1
}  // namespace sensors
}  // namespace hardware
}  // namespace android
