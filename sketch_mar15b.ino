#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// BLE UUID
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHAR_YAW_UUID       "beb5483e-36e1-4688-b7f5-ea07361b26a8"
#define CHAR_PITCH_UUID     "1c95d5e3-d8f7-413a-bf3d-7a2e5d7be87e"
#define CHAR_STEP_DELAY_UUID "a1b2c3d4-0001-4000-8000-00805f9b34fb"
#define CHAR_STEP_SIZE_UUID  "a1b2c3d4-0002-4000-8000-00805f9b34fb"

// ===== 180°舵机参数 =====
#define SERVO_MIN_ANGLE 0
#define SERVO_MAX_ANGLE 180
#define YAW_PIN   13
#define PITCH_PIN 14

int stepDelayMs = 2;
int stepSize    = 10;

bool deviceConnected = false;
bool oldDeviceConnected = false;

BLEServer *pServer = NULL;

// Yaw轴状态
int yawCurrent = 90;
int yawTarget = 90;
unsigned long yawLastStepTime = 0;

// Pitch轴状态
int pitchCurrent = 90;
int pitchTarget = 90;
unsigned long pitchLastStepTime = 0;

// LEDC驱动舵机：角度 -> 脉宽 -> 占空比
void servoWrite(int pin, int angle) {
  int pulseUs = map(angle, 0, 180, 500, 2500);
  int duty = (int)((float)pulseUs / 20000.0 * 16384.0);
  ledcWrite(pin, duty);
}

class MyServerCallbacks : public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("BLE 设备已连接");
  }

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    Serial.println("BLE 设备已断开");
  }
};

class YawCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String value = pCharacteristic->getValue().c_str();
    if (value.length() > 0) {
      int angle = constrain(value.toInt(), SERVO_MIN_ANGLE, SERVO_MAX_ANGLE);
      yawTarget = angle;
      Serial.print("BLE -> Yaw目标角度: ");
      Serial.println(yawTarget);
    }
  }
};

class PitchCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String value = pCharacteristic->getValue().c_str();
    if (value.length() > 0) {
      int angle = constrain(value.toInt(), SERVO_MIN_ANGLE, SERVO_MAX_ANGLE);
      pitchTarget = angle;
      Serial.print("BLE -> Pitch目标角度: ");
      Serial.println(pitchTarget);
    }
  }
};

class StepDelayCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String value = pCharacteristic->getValue().c_str();
    if (value.length() > 0) {
      int v = constrain(value.toInt(), 0, 100);
      stepDelayMs = v;
      Serial.print("BLE -> 步进间隔: ");
      Serial.print(stepDelayMs);
      Serial.println("ms");
    }
  }
};

class StepSizeCallbacks : public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic *pCharacteristic) {
    String value = pCharacteristic->getValue().c_str();
    if (value.length() > 0) {
      int v = constrain(value.toInt(), 1, 180);
      stepSize = v;
      Serial.print("BLE -> 步进度数: ");
      Serial.print(stepSize);
      Serial.println("°");
    }
  }
};

void stepServoTowards(int pin, int &currentAngle, int targetAngle, unsigned long &lastStepTime, const char *name) {
  if (currentAngle == targetAngle) {
    return;
  }

  if (millis() - lastStepTime < (unsigned long)stepDelayMs) {
    return;
  }

  int diff = targetAngle - currentAngle;
  if (abs(diff) <= stepSize) {
    currentAngle = targetAngle;
  } else if (diff > 0) {
    currentAngle += stepSize;
  } else {
    currentAngle -= stepSize;
  }

  currentAngle = constrain(currentAngle, SERVO_MIN_ANGLE, SERVO_MAX_ANGLE);
  servoWrite(pin, currentAngle);
  lastStepTime = millis();

  Serial.print(name);
  Serial.print(" 当前角度: ");
  Serial.println(currentAngle);
}

void setup() {
  Serial.begin(115200);

  // 用LEDC初始化舵机
  ledcAttach(YAW_PIN, 50, 14);
  ledcAttach(PITCH_PIN, 50, 14);
  servoWrite(YAW_PIN, yawCurrent);
  servoWrite(PITCH_PIN, pitchCurrent);
  Serial.println("180度舵机初始化完成（初始90度）");

  // 初始化BLE
  BLEDevice::init("ESP32-Servo-180");
  pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());

  BLEService *pService = pServer->createService(SERVICE_UUID);

  BLECharacteristic *pCharYaw = pService->createCharacteristic(
    CHAR_YAW_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
  );
  pCharYaw->setCallbacks(new YawCallbacks());
  pCharYaw->setValue("90");

  BLECharacteristic *pCharPitch = pService->createCharacteristic(
    CHAR_PITCH_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
  );
  pCharPitch->setCallbacks(new PitchCallbacks());
  pCharPitch->setValue("90");

  BLECharacteristic *pCharStepDelay = pService->createCharacteristic(
    CHAR_STEP_DELAY_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
  );
  pCharStepDelay->setCallbacks(new StepDelayCallbacks());
  pCharStepDelay->setValue("2");

  BLECharacteristic *pCharStepSize = pService->createCharacteristic(
    CHAR_STEP_SIZE_UUID,
    BLECharacteristic::PROPERTY_READ | BLECharacteristic::PROPERTY_WRITE
  );
  pCharStepSize->setCallbacks(new StepSizeCallbacks());
  pCharStepSize->setValue("10");

  pService->start();

  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06);
  pAdvertising->setMaxPreferred(0x12);
  pAdvertising->setAdvertisementType(ADV_TYPE_IND);
  BLEDevice::startAdvertising();

  Serial.println("BLE 已启动，设备名称: ESP32-Servo-180");
  Serial.println("发送 0~180 的角度值控制舵机");
  Serial.println("等待连接...");
}

void loop() {
  // BLE断开后重启广播
  if (!deviceConnected && oldDeviceConnected) {
    delay(500);
    pServer->startAdvertising();
    Serial.println("BLE 重新广播，等待连接...");
    oldDeviceConnected = deviceConnected;
  }

  if (deviceConnected && !oldDeviceConnected) {
    oldDeviceConnected = deviceConnected;
  }

  // Yaw / Pitch 轴平滑跟随目标角度
  stepServoTowards(YAW_PIN, yawCurrent, yawTarget, yawLastStepTime, "Yaw");
  stepServoTowards(PITCH_PIN, pitchCurrent, pitchTarget, pitchLastStepTime, "Pitch");

  delay(2);
}
