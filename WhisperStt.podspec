require "json"

package = JSON.parse(File.read(File.join(__dir__, "package.json")))

Pod::Spec.new do |s|
  s.name         = "WhisperStt"
  s.version      = package["version"]
  s.summary      = package["description"]
  s.homepage     = package["homepage"]
  s.license      = package["license"]
  s.authors      = package["author"]

  s.platforms    = { :ios => min_ios_version_supported }
  s.source       = { :git => "https://github.com/israr002/rn-whisper-stt.git", :tag => "v#{s.version}" }

  # Only this folder: ios/Frameworks holds the built whisper.cpp headers
  s.source_files = "ios/*.{h,m,mm}"
  s.private_header_files = "ios/*.h"

  # whisper.cpp + ggml (Metal backend), built by scripts/build-ios-whisper.sh
  s.vendored_frameworks = "ios/Frameworks/whisper.xcframework"
  s.frameworks = "AVFoundation", "Metal", "Foundation"
  s.library = "c++"
  # Silero VAD v5.1.2 in ggml format (MIT), used through whisper.cpp
  s.resource_bundles = { "WhisperStt" => ["ios/Resources/*.bin"] }

  install_modules_dependencies(s)
end
