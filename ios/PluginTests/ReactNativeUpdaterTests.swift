import XCTest
@testable import Plugin

class ReactNativeUpdaterTests: XCTestCase {

    func testCoreInit() {
        let implementation = ReactNativeUpdaterCore()
        XCTAssertNotNil(implementation)
    }
}
