import Foundation
import CryptoKit

public enum J: Codable, Equatable, Sendable, ExpressibleByStringLiteral, ExpressibleByIntegerLiteral, ExpressibleByBooleanLiteral, ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral {
 case object([String:J]), array([J]), string(String), number(Double), bool(Bool), null
 public init(stringLiteral value:String){self = .string(value)}
 public init(integerLiteral value:Int64){self = .number(Double(value))}
 public init(booleanLiteral value:Bool){self = .bool(value)}
 public init(arrayLiteral elements:J...){self = .array(elements)}
 public init(dictionaryLiteral elements:(String,J)...){self = .object(Dictionary(uniqueKeysWithValues:elements))}
 public init(from decoder:Decoder)throws{let c=try decoder.singleValueContainer();if c.decodeNil(){self = .null}else if let x=try? c.decode(Bool.self){self = .bool(x)}else if let x=try? c.decode(Double.self){self = .number(x)}else if let x=try? c.decode(String.self){self = .string(x)}else if let x=try? c.decode([String:J].self){self = .object(x)}else{self = .array(try c.decode([J].self))}}
 public func encode(to encoder:Encoder)throws{var c=encoder.singleValueContainer();switch self{case .object(let x):try c.encode(x);case .array(let x):try c.encode(x);case .string(let x):try c.encode(x);case .number(let x):try c.encode(x);case .bool(let x):try c.encode(x);case .null:try c.encodeNil()}}
 public subscript(_ key:String)->J{get{o[key] ?? .null}set{var d=o;d[key]=newValue;self = .object(d)}}
 public var o:[String:J]{if case .object(let x)=self{return x};return [:]}
 public var a:[J]{if case .array(let x)=self{return x};return []}
 public var s:String{if case .string(let x)=self{return x};return ""}
 public var n:Double{if case .number(let x)=self{return x};return 0}
 public var i:Int64{n.isFinite && n > Double(Int64.min) && n < Double(Int64.max) ? Int64(n):0}
 public var b:Bool{if case .bool(let x)=self{return x};return false}
 public func text()throws->String{let e=JSONEncoder();e.outputFormatting=[.sortedKeys,.withoutEscapingSlashes];return String(decoding:try e.encode(self),as:UTF8.self)}
 public static func parse(_ text:String)throws->J{try JSONDecoder().decode(J.self,from:Data(text.utf8))}
 public static func num(_ x:Int64)->J{.number(Double(x))}
 public static func strings(_ x:[String])->J{.array(x.map(J.string))}
}
public struct ComedorError:LocalizedError,Equatable {public let message:String;public init(_ message:String){self.message=message};public var errorDescription:String?{message}}
public func require(_ yes:Bool,_ message:String)throws{if !yes{throw ComedorError(message)}}
public enum Wire {
 public static func hex(_ data:some Sequence<UInt8>)->String{data.map{String(format:"%02x",$0)}.joined()}
 public static func hash(_ text:String)->String{hex(SHA256.hash(data:Data(text.utf8)))}
 public static func mac(_ key:String,_ text:String)->String{hex(HMAC<SHA256>.authenticationCode(for:Data(text.utf8),using:SymmetricKey(data:Data(key.utf8))))}
 public static func equal(_ a:String,_ b:String)->Bool{let x=Array(a.utf8),y=Array(b.utf8);guard x.count==y.count else{return false};var n:UInt8=0;for i in x.indices{n |= x[i]^y[i]};return n==0}
 public static func b64(_ data:Data)->String{data.base64EncodedString().replacingOccurrences(of:"+",with:"-").replacingOccurrences(of:"/",with:"_").replacingOccurrences(of:"=",with:"")}
 public static func unb64(_ value:String)throws->Data{let s=value.replacingOccurrences(of:"-",with:"+").replacingOccurrences(of:"_",with:"/");guard let data=Data(base64Encoded:s+String(repeating:"=",count:(4-s.count%4)%4)) else{throw ComedorError("Código incompleto.")};return data}
 public static func random()->String{hex(SymmetricKey(size:.bits256).withUnsafeBytes{Array($0)})}
 public static func matches(_ s:String,_ pattern:String)->Bool{guard let range=s.range(of:"^(?:"+pattern+")$",options:.regularExpression)else{return false};return range==s.startIndex..<s.endIndex}
 public static func p256(_ key:String,_ message:String,_ signature:String,urlSafe:Bool=false)throws->Bool{let kd=try urlSafe ? unb64(key):decodeBase64(key);let sd=try urlSafe ? unb64(signature):decodeBase64(signature);let k=try P256.Signing.PublicKey(derRepresentation:kd);return k.isValidSignature(try P256.Signing.ECDSASignature(derRepresentation:sd),for:Data(message.utf8))}
 public static func decodeBase64(_ value:String)throws->Data{guard let data=Data(base64Encoded:value)else{throw ComedorError("Firma incompleta.")};return data}
 public static var now:Int64{Int64(Date().timeIntervalSince1970*1000)}
 public static func day(_ time:Int64)->String{date(time,"yyyy-MM-dd")}
 public static func date(_ time:Int64,_ format:String="dd/MM/yyyy HH:mm")->String{let f=DateFormatter();f.locale=Locale(identifier:"es_BO");f.timeZone=TimeZone(identifier:"America/La_Paz")!;f.dateFormat=format;return f.string(from:Date(timeIntervalSince1970:Double(time)/1000))}
}
