# ChannelBrowserProto

A proto-based version of the ChannelBrowser Aliucord plugin.

## Features
- Uses Protocol Buffers for all data serialization/deserialization
- Example .proto file for channel data
- Example HTTP request parsing protobuf response

## Setup
- Requires protobuf codegen (see build.gradle.kts)
- Place your .proto files in `src/main/proto/`
- Example usage in `ChannelBrowserProto.kt`

## Example
Fetches a protobuf-encoded channel list from a remote server and parses it.

---

