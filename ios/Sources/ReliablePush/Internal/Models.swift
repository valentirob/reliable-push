import Foundation

// JSON goes through `JSONSerialization` and these small mappings, not Codable: the synthesized conformances and the
// generic JSONEncoder/JSONDecoder paths were the largest part of the binary (size budget, spec §9.2). Field names are
// those of `openapi.yaml`; a missing or mistyped required field makes the whole value nil (as Codable would fail).

typealias JSONObject = [String: Any]

struct Registration: Sendable {
    let deviceId: String
    let deviceToken: String
    let expiresAt: Date

    init(deviceId: String, deviceToken: String, expiresAt: Date) {
        self.deviceId = deviceId
        self.deviceToken = deviceToken
        self.expiresAt = expiresAt
    }

    init?(json: JSONObject) {
        guard let id = json["deviceId"] as? String, let token = IssuedToken(json: json) else { return nil }
        self.init(deviceId: id, deviceToken: token.deviceToken, expiresAt: token.expiresAt)
    }
}

struct IssuedToken: Sendable {
    let deviceToken: String
    let expiresAt: Date

    init?(json: JSONObject) {
        guard let token = json["deviceToken"] as? String, let exp = ISO8601Coding.date(json["expiresAt"]) else { return nil }
        deviceToken = token
        expiresAt = exp
    }
}

enum EventType: String, Sendable {
    case delivered = "DELIVERED"
    case read = "READ"
}

struct QueuedEvent: Sendable {
    let trackingId: String
    let type: EventType
    let occurredAt: Date

    init(trackingId: String, type: EventType, occurredAt: Date) {
        self.trackingId = trackingId
        self.type = type
        self.occurredAt = occurredAt
    }

    init?(json: JSONObject) {
        guard let id = json["trackingId"] as? String, let raw = json["type"] as? String, let type = EventType(rawValue: raw),
              let at = ISO8601Coding.date(json["occurredAt"]) else { return nil }
        self.init(trackingId: id, type: type, occurredAt: at)
    }

    var json: JSONObject { ["trackingId": trackingId, "type": type.rawValue, "occurredAt": ISO8601Coding.string(occurredAt)] }
}

struct EventOutcome: Sendable {
    let trackingId: String
    let outcome: String

    init?(json: Any) {
        guard let json = json as? JSONObject, let id = json["trackingId"] as? String,
              let outcome = json["outcome"] as? String else { return nil }
        trackingId = id
        self.outcome = outcome
    }
}

enum ISO8601Coding {
    // ISO8601DateFormatter is thread-safe; building one per date made large queues quadratic.
    private nonisolated(unsafe) static let withFraction: ISO8601DateFormatter = make([.withInternetDateTime, .withFractionalSeconds])
    private nonisolated(unsafe) static let withoutFraction: ISO8601DateFormatter = make([.withInternetDateTime])

    private static func make(_ options: ISO8601DateFormatter.Options) -> ISO8601DateFormatter {
        let f = ISO8601DateFormatter()
        f.formatOptions = options
        return f
    }

    /// Always with milliseconds.
    static func string(_ date: Date) -> String { withFraction.string(from: date) }

    /// A string with or without fractional seconds; nil for anything else.
    static func date(_ value: Any?) -> Date? {
        guard let s = value as? String else { return nil }
        return withFraction.date(from: s) ?? withoutFraction.date(from: s)
    }
}

enum JSON {
    /// The top-level object of `data`, or nil when it is not a JSON object.
    static func object(_ data: Data) -> JSONObject? {
        (try? JSONSerialization.jsonObject(with: data)) as? JSONObject
    }

    static func data(_ value: Any) -> Data? {
        try? JSONSerialization.data(withJSONObject: value)
    }
}
