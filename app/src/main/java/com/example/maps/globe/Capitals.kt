package com.example.maps.globe

/** A tour stop. Names are in French, as shown in the caption. */
data class Capital(val city: String, val country: String, val lat: Double, val lon: Double) {
    val point get() = GlobeCamera.GeoPoint(lat, lon)
}

/** Capitals of large countries, ordered so the tour loops smoothly around the world. */
object Capitals {
    val TOUR = listOf(
        Capital("Paris", "France", 48.8566, 2.3522),
        Capital("Berlin", "Allemagne", 52.5200, 13.4050),
        Capital("Moscou", "Russie", 55.7558, 37.6173),
        Capital("Pékin", "Chine", 39.9042, 116.4074),
        Capital("Tokyo", "Japon", 35.6762, 139.6503),
        Capital("Canberra", "Australie", -35.2809, 149.1300),
        Capital("Jakarta", "Indonésie", -6.2088, 106.8456),
        Capital("New Delhi", "Inde", 28.6139, 77.2090),
        Capital("Le Caire", "Égypte", 30.0444, 31.2357),
        Capital("Pretoria", "Afrique du Sud", -25.7479, 28.2293),
        Capital("Brasília", "Brésil", -15.7939, -47.8828),
        Capital("Buenos Aires", "Argentine", -34.6037, -58.3816),
        Capital("Mexico", "Mexique", 19.4326, -99.1332),
        Capital("Washington", "États-Unis", 38.9072, -77.0369),
        Capital("Ottawa", "Canada", 45.4215, -75.6972),
    )
}
