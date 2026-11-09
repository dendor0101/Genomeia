package io.github.some_example_name.physics

/** Параметры одного контактного решателя; разные сессии не переключают друг друга. */
class ContactConfig(parameters: Map<String, String> = emptyMap()) {
    private val values = parameters.toSortedMap()
    operator fun get(name: String): String? = values[name]
    val CONTACT_OMEGA_H = this["CT_OMEGA"]?.toDoubleOrNull() ?: 1.0
    /**
     * Запас широкой фазы в долях наибольшего радиуса контакта. Меньше запас — меньше пар-кандидатов (их потом
     * обходят поиск пробоя и CCD), но чаще перекладка. На результат не влияет: контрольная сумма мира одна при
     * 0.25, 0.5, 1 и 1.5. Замер 09.11, мс на тик, 8 воркеров, по три прогона: 34 червя (16 подшагов) 1.0 — 113,
     * 0.5 — 106, 0.25 — 105, 1.5 — 123; толпа без мышц (32 подшага) 1.0 — 88, 0.5 — 86, 0.25 — 92; листья не касаются — без разницы.
     */
    val BP_MARGIN = this["CT_MARGIN"]?.toDoubleOrNull() ?: 0.5
    val CT_SWEEP_FLIP = (this["CT_SWEEP"] ?: "1") != "0"
    val CCD_ALWAYS = (this["CT_CCD_ALWAYS"] ?: "1") != "0"
    val CCD_GATE = this["CT_CCD_GATE"]?.toDoubleOrNull() ?: 1.0
    val PIERCE_TEAR = this["CT_PIERCE"] != "0"
    val PIERCE_DEPTH = this["CT_PIERCE_DEPTH"]?.toDoubleOrNull() ?: 0.0
    val REST_FULL = this["CT_RESTFULL"] == "1"
    val BP_CHECK = this["CT_BPCHECK"] != null
    val XPBD_WARM_START = this["CT_WARM"] == "1"
    val abLegacy = this["CT_LEGACY"] != null
    val CONTACT_COMPLIANCE = this["CT_SOFT"]?.toDoubleOrNull() ?: 1.0e-8
    val CONTACT_OMEGA_PAIR = this["CT_OMEGA_PAIR"]?.toDoubleOrNull() ?: 2.0
    val CONTACT_SEAL = this["CT_SEAL"]?.toDoubleOrNull() ?: 1.05
}
