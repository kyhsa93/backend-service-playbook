package com.example.accountservice.common

import java.util.UUID

fun generateId(): String = UUID.randomUUID().toString().replace("-", "")
