import Foundation

/// Setara AppConfig.kt di shell Android (smile-2.1).
enum AppConfig {
    static let appId = "co.id.multifab.smile"
    static let appName = "Smile Multifab"
    static let webURL = URL(string: "https://smile.multifab.co.id")!
    static let jsBridgeName = "SmileNative"
    static let userAgentSuffix = " SmileMultifab/2.6.13 iOS"

    static let allowedHosts: Set<String> = [
        "smile.multifab.co.id",
        "multifab.co.id",
        "api-mms-erp.multifab.co.id",
        "api-mms-log.multifab.co.id",
    ]

    static let erpApiBase = "https://api-mms-erp.multifab.co.id"
    static let logApiBase = "https://api-mms-log.multifab.co.id"
}
