package com.typesafe.conductr.clientlib.scala.models

import java.util.UUID

/**
 * HTTP result for an unload bundle request
 */
sealed trait BundleUnloadResult

/**
 * Represents a HTTP success result for an unload bundle request
 * @param requestId The unique request id generated by the ConductR control server
 */
final case class BundleUnloadSuccess(requestId: UUID) extends BundleUnloadResult

/**
 * Represents a HTTP failure result for an bundle request
 * @param code The HTTP status code
 * @param error The error message
 */
final case class BundleUnloadFailure(code: Int, error: String) extends HttpFailure with BundleUnloadResult