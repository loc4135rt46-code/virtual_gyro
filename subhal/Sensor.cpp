// Sensor.cpp
// Rut gon tu tham khao AOSP that: .../fake_subhal/Sensor.cpp
// VirtualGyroSensor::readEvents() thay data gia lap bang data that doc tu
// /dev/socket/virtgyro (gyro_relay ghi vao).

#include "Sensor.h"

#include <errno.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <time.h>
#include <unistd.h>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>

#include <android/log.h>

#define LOG_TAG "VirtualGyroSubHal"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace android {
namespace hardware {
namespace sensors {
namespace V2_1 {
namespace subhal {
namespace implementation {

static constexpr const char* kVirtGyroSocketPath = "/dev/socket/virtgyro";

int64_t Sensor::nowBootNanos() {
    struct timespec t;
    clock_gettime(CLOCK_BOOTTIME, &t);
    return static_cast<int64_t>(t.tv_sec) * 1000000000LL + t.tv_nsec;
}

Sensor::Sensor(int32_t sensorHandle, ISensorsEventCallback* callback)
    : mIsEnabled(false),
      mSamplingPeriodNs(0),
      mLastSampleTimeNs(0),
      mStopThread(false),
      mCallback(callback),
      mMode(OperationMode::NORMAL) {
    mSensorInfo.sensorHandle = sensorHandle;
    mSensorInfo.vendor = "VirtualGyro";
    mSensorInfo.version = 1;
    constexpr float kDefaultMaxDelayUs = 1000 * 1000;
    mSensorInfo.maxDelay = kDefaultMaxDelayUs;
    mSensorInfo.fifoReservedEventCount = 0;
    mSensorInfo.fifoMaxEventCount = 0;
    mSensorInfo.requiredPermission = "";
    mSensorInfo.flags = 0;
    mRunThread = std::thread(startThread, this);
}

Sensor::~Sensor() {
    {
        std::unique_lock<std::mutex> lock(mRunMutex);
        mStopThread = true;
        mIsEnabled = false;
        mWaitCV.notify_all();
    }
    mRunThread.join();
}

const SensorInfo& Sensor::getSensorInfo() const {
    return mSensorInfo;
}

void Sensor::batch(int64_t samplingPeriodNs) {
    samplingPeriodNs = std::clamp(samplingPeriodNs,
                                  static_cast<int64_t>(mSensorInfo.minDelay) * 1000,
                                  static_cast<int64_t>(mSensorInfo.maxDelay) * 1000);

    std::unique_lock<std::mutex> lock(mRunMutex);
    if (mSamplingPeriodNs != samplingPeriodNs) {
        mSamplingPeriodNs = samplingPeriodNs;
        mWaitCV.notify_all();
    }
}

void Sensor::activate(bool enable) {
    if (mIsEnabled != enable) {
        std::unique_lock<std::mutex> lock(mRunMutex);
        mIsEnabled = enable;
        mWaitCV.notify_all();
    }
}

Result Sensor::flush() {
    if (!mIsEnabled) {
        return Result::BAD_VALUE;
    }
    Event ev;
    ev.sensorHandle = mSensorInfo.sensorHandle;
    ev.sensorType = SensorType::META_DATA;
    ev.u.meta.what = ::android::hardware::sensors::V1_0::MetaDataEventType::META_DATA_FLUSH_COMPLETE;
    std::vector<Event> evs{ev};
    mCallback->postEvents(evs, isWakeUpSensor());
    return Result::OK;
}

void Sensor::startThread(Sensor* sensor) {
    sensor->run();
}

void Sensor::run() {
    std::unique_lock<std::mutex> runLock(mRunMutex);

    while (!mStopThread) {
        if (!mIsEnabled || mMode == OperationMode::DATA_INJECTION) {
            mWaitCV.wait(runLock, [&] {
                return ((mIsEnabled && mMode == OperationMode::NORMAL) || mStopThread);
            });
        } else {
            int64_t now = nowBootNanos();
            int64_t nextSampleTime = mLastSampleTimeNs + mSamplingPeriodNs;

            if (now >= nextSampleTime) {
                mLastSampleTimeNs = now;
                nextSampleTime = mLastSampleTimeNs + mSamplingPeriodNs;
                mCallback->postEvents(readEvents(), isWakeUpSensor());
            }

            mWaitCV.wait_for(runLock, std::chrono::nanoseconds(nextSampleTime - now));
        }
    }
}

bool Sensor::isWakeUpSensor() {
    return mSensorInfo.flags & static_cast<uint32_t>(SensorFlagBits::WAKE_UP);
}

void Sensor::setOperationMode(OperationMode mode) {
    if (mMode != mode) {
        std::unique_lock<std::mutex> lock(mRunMutex);
        mMode = mode;
        mWaitCV.notify_all();
    }
}

// ---------------------------------------------------------------------------
// VirtualGyroSensor
// ---------------------------------------------------------------------------

VirtualGyroSensor::VirtualGyroSensor(int32_t sensorHandle, ISensorsEventCallback* callback)
    : Sensor(sensorHandle, callback),
      mSocketFd(-1),
      mStopSocketThread(false),
      mLastX(0),
      mLastY(0),
      mLastZ(0),
      mHasSample(false) {
    mSensorInfo.name = "Virtual Gyro Sensor";
    mSensorInfo.type = SensorType::GYROSCOPE;
    mSensorInfo.typeAsString = "android.sensor.gyroscope";
    mSensorInfo.maxRange = 1000.0f * static_cast<float>(M_PI) / 180.0f;
    mSensorInfo.resolution = 1000.0f * static_cast<float>(M_PI) / (180.0f * 32768.0f);
    mSensorInfo.power = 0.001f;
    mSensorInfo.minDelay = 2500;         // 2.5ms -> toi da ~400Hz
    mSensorInfo.maxDelay = 1000 * 1000;  // 1s
    mSensorInfo.flags |= static_cast<uint32_t>(SensorFlagBits::CONTINUOUS_MODE);

    mSocketThread = std::thread(&VirtualGyroSensor::socketReaderLoop, this);
}

VirtualGyroSensor::~VirtualGyroSensor() {
    mStopSocketThread = true;
    int fd = mSocketFd.load();
    if (fd >= 0) {
        shutdown(fd, SHUT_RDWR);
        close(fd);
    }
    if (mSocketThread.joinable()) {
        mSocketThread.join();
    }
}

void VirtualGyroSensor::socketReaderLoop() {
    int fd = socket(AF_UNIX, SOCK_DGRAM, 0);
    if (fd < 0) {
        ALOGE("khong tao duoc socket AF_UNIX: %s", strerror(errno));
        return;
    }

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, kVirtGyroSocketPath, sizeof(addr.sun_path) - 1);

    unlink(kVirtGyroSocketPath);  // xoa file cu sot lai neu HAL restart
    if (bind(fd, reinterpret_cast<struct sockaddr*>(&addr), sizeof(addr)) < 0) {
        ALOGE("khong bind duoc %s: %s", kVirtGyroSocketPath, strerror(errno));
        close(fd);
        return;
    }
    chmod(kVirtGyroSocketPath, 0666);

    mSocketFd.store(fd);
    ALOGI("da mo %s, cho gyro_relay ghi du lieu vao", kVirtGyroSocketPath);

    uint8_t buf[64];
    while (!mStopSocketThread) {
        ssize_t n = recv(fd, buf, sizeof(buf), 0);
        if (n < 0) {
            if (errno == EINTR) continue;
            break;  // socket bi dong (destructor goi shutdown) -> thoat vong lap
        }
        if (n == 12) {
            float x, y, z;
            memcpy(&x, buf, 4);
            memcpy(&y, buf + 4, 4);
            memcpy(&z, buf + 8, 4);
            std::lock_guard<std::mutex> lock(mSampleMutex);
            mLastX = x;
            mLastY = y;
            mLastZ = z;
            mHasSample = true;
        }
        // goi khac 12 byte -> bo qua, doc tiep
    }
}

std::vector<Event> VirtualGyroSensor::readEvents() {
    std::vector<Event> events;
    Event event;
    event.sensorHandle = mSensorInfo.sensorHandle;
    event.sensorType = mSensorInfo.type;
    event.timestamp = nowBootNanos();

    {
        std::lock_guard<std::mutex> lock(mSampleMutex);
        event.u.vec3.x = mLastX;
        event.u.vec3.y = mLastY;
        event.u.vec3.z = mLastZ;
    }
    event.u.vec3.status = mHasSample ? SensorStatus::ACCURACY_HIGH : SensorStatus::ACCURACY_LOW;
    events.push_back(event);
    return events;
}

}  // namespace implementation
}  // namespace subhal
}  // namespace V2_1
}  // namespace sensors
}  // namespace hardware
}  // namespace android
