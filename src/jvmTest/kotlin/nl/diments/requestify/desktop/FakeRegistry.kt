package nl.diments.requestify.desktop

/** HKCU in memory: key → name → value. */
internal class FakeRegistry : RegistryPort {
    val strings = HashMap<String, MutableMap<String, String>>()
    val binaries = HashMap<String, MutableMap<String, ByteArray>>()
    var writes = 0
    override fun getString(key: String, name: String) = strings[key]?.get(name)
    override fun setString(key: String, name: String, value: String) { writes++; strings.getOrPut(key) { HashMap() }[name] = value }
    override fun delete(key: String, name: String) { writes++; strings[key]?.remove(name); binaries[key]?.remove(name) }
    override fun getBinary(key: String, name: String) = binaries[key]?.get(name)
    override fun setBinary(key: String, name: String, value: ByteArray) { writes++; binaries.getOrPut(key) { HashMap() }[name] = value }
}
