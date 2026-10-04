/*
 * Nexus Bridge for M5Stack StackChan.
 *
 * The official StackChan boot path remains untouched. startNexusBridge() only
 * creates a background task. That task waits for the normal boot to settle and
 * for Wi-Fi to be connected before starting the local bridge.
 */
#include "hal.h"

#include <string>
#include <vector>

#include <ArduinoJson.hpp>
#include <esp_http_server.h>
#include <esp_log.h>
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
#include <settings.h>
#include <wifi_manager.h>

static const char* TAG = "NexusBridge";

static httpd_handle_t s_httpd = nullptr;
static TaskHandle_t s_task = nullptr;

static constexpr uint16_t kPort = 8787;
static constexpr char kNs[] = "nexus_bridge";
static constexpr char kPersonality[] = "personality";
static constexpr char kMemory[] = "short_memory";
static constexpr char kVoice[] = "voice";

static std::string get_setting(const char* key, const char* fallback = "")
{
    Settings settings(kNs, false);
    return settings.GetString(key, fallback);
}

static void set_setting(const char* key, const std::string& value)
{
    Settings settings(kNs, true);
    settings.SetString(key, value);
}

static std::string ack(const char* action, bool ok = true, const char* detail = nullptr)
{
    ArduinoJson::JsonDocument doc;
    doc["type"] = "ack";
    doc["for"] = action;
    doc["ok"] = ok;
    if (detail) {
        doc["detail"] = detail;
    }

    std::string out;
    ArduinoJson::serializeJson(doc, out);
    return out;
}

static std::string hello_json()
{
    ArduinoJson::JsonDocument doc;
    auto& wifi = WifiManager::GetInstance();

    doc["type"] = "hello";
    doc["ok"] = true;
    doc["identity"] = "StackChan M5";
    doc["device"] = "StackChan M5";
    doc["role"] = "Nexus work bot";
    doc["bridge"] = "Nexus Bridge";
    doc["bridge_version"] = "2.0.0";
    doc["port"] = kPort;
    doc["ip"] = wifi.GetIpAddress();
    doc["wifi_connected"] = wifi.IsConnected();
    doc["voice"] = get_setting(kVoice, "Cutey");
    doc["lang"] = "en-US";

    std::string out;
    ArduinoJson::serializeJson(doc, out);
    return out;
}

static std::string status_json()
{
    ArduinoJson::JsonDocument doc;
    auto& wifi = WifiManager::GetInstance();

    doc["type"] = "status";
    doc["ok"] = true;
    doc["device"] = "StackChan M5";
    doc["role"] = "Nexus work bot";
    doc["bridge_version"] = "2.0.0";
    doc["port"] = kPort;
    doc["ip"] = wifi.GetIpAddress();
    doc["wifi_connected"] = wifi.IsConnected();
    doc["battery"] = GetHAL().getBatteryLevel();
    doc["voice"] = get_setting(kVoice, "Cutey");
    doc["personality_saved"] = !get_setting(kPersonality).empty();
    doc["memory_saved"] = !get_setting(kMemory).empty();

    std::string out;
    ArduinoJson::serializeJson(doc, out);
    return out;
}

static esp_err_t ws_send(httpd_req_t* req, const std::string& text)
{
    httpd_ws_frame_t frame = {};
    frame.type = HTTPD_WS_TYPE_TEXT;
    frame.payload = reinterpret_cast<uint8_t*>(const_cast<char*>(text.data()));
    frame.len = text.size();
    return httpd_ws_send_frame(req, &frame);
}

static std::string handle_command(const std::string& payload)
{
    ArduinoJson::JsonDocument doc;
    if (ArduinoJson::deserializeJson(doc, payload)) {
        return ack("parse", false, "invalid json");
    }

    const std::string type = doc["type"] | "";

    if (type == "hello") {
        return hello_json();
    }

    if (type == "status" || type == "get_state") {
        return status_json();
    }

    if (type == "ping") {
        ArduinoJson::JsonDocument out;
        out["type"] = "pong";
        out["ok"] = true;
        out["device"] = "StackChan M5";
        std::string text;
        ArduinoJson::serializeJson(out, text);
        return text;
    }

    if (type == "personality" || type == "sync_personality") {
        const char* value = doc["text"] | "";
        if (!*value) return ack("personality", false, "missing text");
        set_setting(kPersonality, value);
        return ack("personality");
    }

    // Match the Nexus app exactly, while keeping compatibility with earlier names.
    if (type == "short_term_memory" || type == "memory" || type == "sync_memory") {
        const char* value = doc["text"] | "";
        if (!*value) return ack("short_term_memory", false, "missing text");
        set_setting(kMemory, value);
        return ack("short_term_memory");
    }

    if (type == "voice") {
        const char* value = doc["voice"] | "";
        if (!*value) value = doc["name"] | "";
        if (!*value) value = "Cutey";
        set_setting(kVoice, value);
        return ack("voice");
    }

    if (type == "speak") {
        const char* value = doc["text"] | "";
        if (!*value) return ack("speak", false, "missing text");

        // Visual proof of the live link. Physical Cutey TTS is not implemented yet.
        WsTextMessage_t msg;
        msg.name = "Nexus";
        msg.content = value;
        GetHAL().onWsTextMessage.emit(msg);

        ArduinoJson::JsonDocument out;
        out["type"] = "ack";
        out["for"] = "speak";
        out["ok"] = true;
        out["displayed"] = true;
        out["audio_streamed"] = false;
        std::string text;
        ArduinoJson::serializeJson(out, text);
        return text;
    }

    return ack(type.empty() ? "unknown" : type.c_str(), false, "unknown command");
}

static esp_err_t root_handler(httpd_req_t* req)
{
    httpd_resp_set_type(req, "text/plain");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_sendstr(req, "StackChan M5 Nexus Bridge online");
}

static esp_err_t status_handler(httpd_req_t* req)
{
    const std::string body = status_json();
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_send(req, body.c_str(), body.size());
}

static esp_err_t ws_handler(httpd_req_t* req)
{
    if (req->method == HTTP_GET) {
        ESP_LOGI(TAG, "Nexus client connected");
        return ESP_OK;
    }

    httpd_ws_frame_t frame = {};
    esp_err_t ret = httpd_ws_recv_frame(req, &frame, 0);
    if (ret != ESP_OK) {
        return ret;
    }

    if (frame.len == 0) {
        return ESP_OK;
    }

    if (frame.type != HTTPD_WS_TYPE_TEXT) {
        return ws_send(req, ack("binary", false, "text json only"));
    }

    std::vector<uint8_t> buffer(frame.len + 1, 0);
    frame.payload = buffer.data();

    ret = httpd_ws_recv_frame(req, &frame, frame.len);
    if (ret != ESP_OK) {
        return ret;
    }

    const std::string payload(reinterpret_cast<char*>(buffer.data()), frame.len);
    return ws_send(req, handle_command(payload));
}

static bool start_server()
{
    if (s_httpd) {
        return true;
    }

    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = kPort;
    config.ctrl_port = 32769;
    config.max_uri_handlers = 6;
    config.max_open_sockets = 4;
    config.lru_purge_enable = true;
    config.stack_size = 6144;

    esp_err_t ret = httpd_start(&s_httpd, &config);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Server start failed: %s", esp_err_to_name(ret));
        s_httpd = nullptr;
        return false;
    }

    httpd_uri_t root = {};
    root.uri = "/nexus";
    root.method = HTTP_GET;
    root.handler = root_handler;

    httpd_uri_t status = {};
    status.uri = "/nexus/status";
    status.method = HTTP_GET;
    status.handler = status_handler;

    httpd_uri_t ws = {};
    ws.uri = "/nexus/ws";
    ws.method = HTTP_GET;
    ws.handler = ws_handler;
    ws.is_websocket = true;
    ws.handle_ws_control_frames = false;

    if (httpd_register_uri_handler(s_httpd, &root) != ESP_OK ||
        httpd_register_uri_handler(s_httpd, &status) != ESP_OK ||
        httpd_register_uri_handler(s_httpd, &ws) != ESP_OK) {
        ESP_LOGE(TAG, "URI registration failed");
        httpd_stop(s_httpd);
        s_httpd = nullptr;
        return false;
    }

    if (get_setting(kVoice).empty()) {
        set_setting(kVoice, "Cutey");
    }

    ESP_LOGI(TAG, "Nexus Bridge online at port %u", kPort);
    return true;
}

static void bridge_task(void*)
{
    // Give the unchanged official boot sequence time to finish completely.
    vTaskDelay(pdMS_TO_TICKS(15000));

    while (!WifiManager::GetInstance().IsConnected()) {
        vTaskDelay(pdMS_TO_TICKS(1000));
    }

    // Retry without ever blocking StackChan's main UI if the port is temporarily busy.
    while (!start_server()) {
        vTaskDelay(pdMS_TO_TICKS(5000));
    }

    s_task = nullptr;
    vTaskDelete(nullptr);
}

void Hal::startNexusBridge()
{
    if (s_task || s_httpd) {
        return;
    }

    BaseType_t ok = xTaskCreate(
        bridge_task,
        "nexus_bridge",
        6144,
        nullptr,
        1,
        &s_task
    );

    if (ok != pdPASS) {
        s_task = nullptr;
        ESP_LOGE(TAG, "Could not create Nexus bridge task");
    }
}
