package com.messenger.client.services

import io.ktor.client.HttpClient

expect fun createHttpClient(): HttpClient
