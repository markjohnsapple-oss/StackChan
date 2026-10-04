/*
 * Nexus Bridge for StackChan M5
 * Local-only WebSocket control bridge for the Nexus companion app.
 */
#include "hal.h"
#include "board/hal_bridge.h"

#include <algorithm>
#include <string>
#include <vector>

#include <ArduinoJson.hpp>
#include <application.h>
#include <esp_http_server.h>
#include <esp_log.h>
#include <settings.h>
#include <stackchan/stackchan.h>
#include <wifi_manager.h>

static const char* TAG = "NexusBridge";
static httpd_handle_t s_nexus_httpd = nullptr;

static constexpr char kSettingsNs[] = "nexus_bridge";
static constexpr char kPersonalityKey[] = "personality";
static constexpr char kMemoryKey[] = "short_memory";
static constexpr char kVoiceKey[] = "voice";
static constexpr uint16_t kBridgePort = 8765;

static std::string get_setting(const char* key, const char* fallback = "")
{
    Settings settings(kSettingsNs, false);
    return settings.GetString(key, fallback);
}

static void set_setting(const char* key, const std::string& value)
{
    Settings settings(kSettingsNs, true);
    settings.SetString(key, value);
}

static std::string build_status_json()
{
    ArduinoJson::JsonDocument doc;
    auto& wifi = WifiManager::GetInstance();

    doc["type"] = "status";
    doc["ok"] = true;
    doc["device"] = "StackChan M5";
    doc["role"] = "Nexus work bot";
    doc["bridge"] = "Nexus Bridge";
    doc["bridge_version"] = "1.0.0";
    doc["port"] = kBridgePort;
    doc["ip"] = wifi.GetIpAddress();
    doc["wifi_connected"] = wifi.IsConnected();
    doc["battery"] = GetHAL().getBatteryLevel();
    doc["voice"] = get_setting(kVoiceKey, "Cutey (en-US)");
    doc["personality_saved"] = !get_setting(kPersonalityKey).empty();
    doc["memory_saved"] = !get_setting(kMemoryKey).empty();

    std::string out;
    ArduinoJson::serializeJson(doc, out);
    return out;
}

static esp_err_t send_ws_text(httpd_req_t* req, const std::string& text)
{
    httpd_ws_frame_t frame = {};
    frame.type = HTTPD_WS_TYPE_TEXT;
    frame.payload = reinterpret_cast<uint8_t*>(const_cast<char*>(text.data()));
    frame.len = text.size();
    return httpd_ws_send_frame(req, &frame);
}

static std::string make_ack(const char* action, bool ok = true, const char* detail = nullptr)
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

static void show_nexus_message(const std::string& text)
{
    WsTextMessage_t msg;
    msg.name = "Nexus";
    msg.content = text;
    GetHAL().onWsTextMessage.emit(msg);

    if (hal_bridge::is_xiaozhi_ready()) {
        Application::GetInstance().Schedule([text]() {
            auto& app = Application::GetInstance();
            app.Alert("NEXUS", text.c_str(), "happy");
        });
    }
}

static std::string handle_nexus_command(const std::string& payload)
{
    ArduinoJson::JsonDocument doc;
    const auto err = ArduinoJson::deserializeJson(doc, payload);
    if (err) {
        return make_ack("parse", false, "invalid json");
    }

    const char* type_c = doc["type"] | "";
    const std::string type(type_c);

    if (type == "hello" || type == "status" || type == "get_state") {
        return build_status_json();
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
        const char* text = doc["text"] | "";
        if (!*text) return make_ack("personality", false, "missing text");
        set_setting(kPersonalityKey, text);
        return make_ack("personality");
    }

    if (type == "memory" || type == "sync_memory") {
        const char* text = doc["text"] | "";
        if (!*text) return make_ack("memory", false, "missing text");
        set_setting(kMemoryKey, text);
        return make_ack("memory");
    }

    if (type == "voice") {
        const char* name = doc["name"] | "Cutey (en-US)";
        set_setting(kVoiceKey, name);
        return make_ack("voice");
    }

    if (type == "speak") {
        const char* text = doc["text"] | "";
        if (!*text) return make_ack("speak", false, "missing text");

        // Phase 1: prove the live link on the physical robot.
        // The text is shown on StackChan and acknowledged immediately.
        // Real streamed TTS audio is added in the next bridge phase.
        show_nexus_message(text);

        ArduinoJson::JsonDocument out;
        out["type"] = "ack";
        out["for"] = "speak";
        out["ok"] = true;
        out["displayed"] = true;
        out["audio_streamed"] = false;
        std::string response;
        ArduinoJson::serializeJson(out, response);
        return response;
    }

    if (type == "move") {
        const int yaw = std::clamp(doc["yaw"] | 0, -90, 90);
        const int pitch = std::clamp(doc["pitch"] | 0, 0, 90);
        const int speed = std::clamp(doc["speed"] | 250, 100, 1000);

        {
            LvglLockGuard lock;
            GetStackChan().motion().moveWithSpeed(yaw * 10, pitch * 10, speed);
        }
        return make_ack("move");
    }

    if (type == "home") {
        {
            LvglLockGuard lock;
            GetStackChan().motion().goHome(350);
        }
        return make_ack("home");
    }

    if (type == "led") {
        const int r = std::clamp(doc["r"] | 0, 0, 168);
        const int g = std::clamp(doc["g"] | 0, 0, 168);
        const int b = std::clamp(doc["b"] | 0, 0, 168);
        {
            LvglLockGuard lock;
            GetStackChan().leftNeonLight().setColor(r, g, b);
            GetStackChan().rightNeonLight().setColor(r, g, b);
        }
        return make_ack("led");
    }

    return make_ack(type.empty() ? "unknown" : type.c_str(), false, "unknown command");
}

static esp_err_t nexus_status_handler(httpd_req_t* req)
{
    const std::string body = build_status_json();
    httpd_resp_set_type(req, "application/json");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_send(req, body.c_str(), body.size());
}

static esp_err_t nexus_root_handler(httpd_req_t* req)
{
    const char* body = "StackChan M5 Nexus Bridge online";
    httpd_resp_set_type(req, "text/plain");
    httpd_resp_set_hdr(req, "Access-Control-Allow-Origin", "*");
    return httpd_resp_sendstr(req, body);
}

static esp_err_t nexus_ws_handler(httpd_req_t* req)
{
    // Initial HTTP GET is the WebSocket upgrade handshake.
    if (req->method == HTTP_GET) {
        ESP_LOGI(TAG, "Nexus WebSocket client connected");
        return ESP_OK;
    }

    httpd_ws_frame_t frame = {};
    frame.type = HTTPD_WS_TYPE_TEXT;

    esp_err_t ret = httpd_ws_recv_frame(req, &frame, 0);
    if (ret != ESP_OK) {
        return ret;
    }

    if (frame.len == 0) {
        return ESP_OK;
    }

    if (frame.type != HTTPD_WS_TYPE_TEXT) {
        return send_ws_text(req, make_ack("binary", false, "text json only"));
    }

    std::vector<uint8_t> buffer(frame.len + 1, 0);
    frame.payload = buffer.data();

    ret = httpd_ws_recv_frame(req, &frame, frame.len);
    if (ret != ESP_OK) {
        return ret;
    }

    const std::string payload(reinterpret_cast<char*>(buffer.data()), frame.len);
    ESP_LOGI(TAG, "RX %u bytes", static_cast<unsigned>(payload.size()));

    return send_ws_text(req, handle_nexus_command(payload));
}

void Hal::startNexusBridge()
{
    if (s_nexus_httpd) {
        return;
    }

    httpd_config_t config = HTTPD_DEFAULT_CONFIG();
    config.server_port = kBridgePort;
    config.ctrl_port = 32769;
    config.max_uri_handlers = 6;
    config.max_open_sockets = 4;
    config.lru_purge_enable = true;
    config.stack_size = 6144;

    const esp_err_t ret = httpd_start(&s_nexus_httpd, &config);
    if (ret != ESP_OK) {
        ESP_LOGE(TAG, "Failed to start bridge server: %s", esp_err_to_name(ret));
        s_nexus_httpd = nullptr;
        return;
    }

    httpd_uri_t root_uri = {};
    root_uri.uri = "/nexus";
    root_uri.method = HTTP_GET;
    root_uri.handler = nexus_root_handler;

    httpd_uri_t status_uri = {};
    status_uri.uri = "/nexus/status";
    status_uri.method = HTTP_GET;
    status_uri.handler = nexus_status_handler;

    httpd_uri_t ws_uri = {};
    ws_uri.uri = "/nexus/ws";
    ws_uri.method = HTTP_GET;
    ws_uri.handler = nexus_ws_handler;
    ws_uri.is_websocket = true;
    ws_uri.handle_ws_control_frames = false;

    ESP_ERROR_CHECK(httpd_register_uri_handler(s_nexus_httpd, &root_uri));
    ESP_ERROR_CHECK(httpd_register_uri_handler(s_nexus_httpd, &status_uri));
    ESP_ERROR_CHECK(httpd_register_uri_handler(s_nexus_httpd, &ws_uri));

    if (get_setting(kVoiceKey).empty()) {
        set_setting(kVoiceKey, "Cutey (en-US)");
    }

    ESP_LOGI(TAG, "Nexus Bridge started on port %u", kBridgePort);
}
