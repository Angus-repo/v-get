import XCTest
@testable import VGet

final class MediaPolicyTests: XCTestCase {
    func testRejectsDeceptiveHostsCredentialsAndCleartext() {
        for value in ["https://fbcdn.net.evil.test/a.mp4", "http://video.fbcdn.net/a.mp4",
                      "https://user:secret@video.fbcdn.net/a.mp4", "https://video.fbcdn.net:444/a.mp4"] {
            XCTAssertFalse(MediaPolicy.allowed(URL(string: value)!, platform: "THREADS"))
        }
        XCTAssertTrue(MediaPolicy.allowed(URL(string: "https://video.fbcdn.net/a.mp4?sig=A%2FB+C")!, platform: "THREADS"))
        XCTAssertFalse(MediaPolicy.allowed(URL(string: "https://video.xhscdn.com/a.mp4")!, platform: "THREADS"))
    }

    func testServiceRequiresExplicitHttpsWithoutCredentials() {
        for value in ["", "http://example.com", "https://user:secret@example.com", "https://example.com?token=secret"] {
            XCTAssertThrowsError(try MediaPolicy.service(value))
        }
        XCTAssertEqual(try MediaPolicy.service("https://engine.example/api").host, "engine.example")
    }

    func testErrorPageCannotBeSavedAsVideo() throws {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        defer { try? FileManager.default.removeItem(at: file) }
        let response = HTTPURLResponse(url: URL(string: "https://video.fbcdn.net/a.mp4")!, statusCode: 200,
                                       httpVersion: nil, headerFields: ["Content-Type": "video/mp4"])!
        try Data("<html>login</html>".utf8).write(to: file)
        XCTAssertThrowsError(try MediaPolicy.validateFile(file, response: response, mp3: false))
        try Data([0, 0, 0, 24, 0x66, 0x74, 0x79, 0x70, 0, 0, 0, 0]).write(to: file)
        XCTAssertNoThrow(try MediaPolicy.validateFile(file, response: response, mp3: false))
        XCTAssertThrowsError(try MediaPolicy.validateFile(file, response: response, mp3: true))
    }
}
