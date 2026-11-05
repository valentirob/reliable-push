import Foundation

struct Registration: Codable, Sendable, Equatable {
    let deviceId: String
    let deviceToken: String
    let expiresAt: Date
}

struct IssuedToken: Codable, Sendable, Equatable {
    let deviceToken: String
    let expiresAt: Date
}

enum EventType: String, Codable, Sendable {
    case delivered = "DELIVERED"
    case read = "READ"
}

struct QueuedEvent: Codable, Sendable, Equatable {
    let trackingId: String
    let type: EventType
    let occurredAt: Date
}

struct EventOutcome: Codable, Sendable, Equatable {
    let trackingId: String
    let outcome: String
}

/// ISO-8601 coding: fractional seconds accepted on input, always written with milliseconds.
enum ISO8601Coding {
    // ISO8601DateFormatter is thread-safe; building one per date made large queues quadratic.
    private nonisolated(unsafe) static let withFraction: ISO8601DateFormatter = make([.withInternetDateTime, .withFractionalSeconds])
    private nonisolated(unsafe) static let withoutFraction: ISO8601DateFormatter = make([.withInternetDateTime])

    private static func make(_ options: ISO8601DateFormatter.Options) -> ISO8601DateFormatter {
        let f = ISO8601DateFormatter()
        f.formatOptions = options
        return f
    }

    private static func formatter(fractional: Bool) -> ISO8601DateFormatter { fractional ? withFraction : withoutFraction }

    static func makeEncoder() -> JSONEncoder {
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .custom { date, enc in
            var container = enc.singleValueContainer()
            try container.encode(formatter(fractional: true).string(from: date))
        }
        return encoder
    }

    static func makeDecoder() -> JSONDecoder {
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .custom { dec in
            let s = try dec.singleValueContainer().decode(String.self)
            if let d = formatter(fractional: true).date(from: s) ?? formatter(fractional: false).date(from: s) { return d }
            throw DecodingError.dataCorrupted(.init(codingPath: dec.codingPath, debugDescription: "invalid ISO-8601 date"))
        }
        return decoder
    }
}
