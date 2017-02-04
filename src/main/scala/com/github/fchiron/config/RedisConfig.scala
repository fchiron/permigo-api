package com.github.fchiron.config

case class RedisConfig(host: String, port: Int, password: Option[String], timeout: Int)
