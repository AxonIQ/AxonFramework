/*
 * Copyright (c) 2010-2026. AxonIQ B.V.
 *
 * Licensed under the AXONIQ TERMS OF SERVICE,
 * Version 29 April 2026 (the "License");
 *
 * The software is available for evaluation use without registration.
 * Continued use beyond the evaluation period requires registration
 * and a commercial license. See the License for the specific language
 * governing permissions and limitations under the License.
 * You may not use this file except in compliance with the License.
 *
 * You may obtain a copy of the License at:
 *  https://www.axoniq.io/legal/terms-of-service
 *
 * For licensing information and to register, visit:
 *  https://www.axoniq.io/pricing
 */
package org.axonframework.examples.workflow.bikerental

import org.axonframework.examples.workflow.bikerental.coreapi.rental.BikeStatus
import org.axonframework.examples.workflow.bikerental.coreapi.rental.RentalStatus
import io.axoniq.framework.testcontainer.AxonServerContainer
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.resttestclient.TestRestTemplate
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.concurrent.TimeUnit

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@AutoConfigureTestRestTemplate
@Testcontainers
class BikeRentalIT {

    companion object {
        @Container
        val axonServer: AxonServerContainer = AxonServerContainer()
            .withAxonServerHostname("localhost")
            .withDevMode(true)
            .withDcbContext(true)

        @JvmStatic
        @DynamicPropertySource
        fun axonProperties(registry: DynamicPropertyRegistry) {
            registry.add("axon.axonserver.servers") {
                "${axonServer.host}:${axonServer.getMappedPort(8124)}"
            }
        }
    }

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Test
    fun `test bike rental process`() {
        restTemplate.postForEntity("/?bikes=5&bikeType=Mountain", null, Void::class.java)

        val bikes = await().atMost(20, TimeUnit.SECONDS).until({
            restTemplate.exchange("/bikes", HttpMethod.GET, null, object : ParameterizedTypeReference<List<BikeStatus>>() {}).body
        }, { it != null && it.isNotEmpty() })
        val bikeId = bikes!![0].bikeId!!

        rentBike(bikeId, "Junie")

        // Each rental starts its own payment workflow, so the same bike can be rented again after its return.
        restTemplate.postForEntity("/returnBike?bikeId=$bikeId&location=Amsterdam", null, Void::class.java)
        await().atMost(20, TimeUnit.SECONDS).untilAsserted {
            assertThat(restTemplate.getForObject("/bikes/$bikeId", BikeStatus::class.java)!!.getStatus())
                .isEqualTo(RentalStatus.AVAILABLE)
        }

        rentBike(bikeId, "Ada")
    }

    private fun rentBike(bikeId: String, renter: String) {
        val rentalReference =
            restTemplate.postForEntity("/requestBike?bikeId=$bikeId&renter=$renter", null, String::class.java).body
        assertThat(rentalReference).isNotNull()

        val paymentId = await().atMost(20, TimeUnit.SECONDS).until({
            restTemplate.getForObject("/findPayment?reference=$rentalReference", String::class.java)
        }, { it != null })
        restTemplate.postForEntity("/acceptPayment?id=$paymentId", null, Void::class.java)

        await().atMost(20, TimeUnit.SECONDS).untilAsserted {
            val bikeStatus = restTemplate.getForObject("/bikes/$bikeId", BikeStatus::class.java)
            assertThat(bikeStatus!!.getStatus()).isEqualTo(RentalStatus.RENTED)
            assertThat(bikeStatus.renter).isEqualTo(renter)
        }
    }
}
