COMPILE_DEPS = CORE_DEPS + [
    "//incubator/api:onos-incubator-api",
]

TEST_DEPS = TEST_ADAPTERS + [
    "//utils/osgi:onlab-osgi-tests",
]

osgi_jar_with_tests (
    deps = COMPILE_DEPS,
    test_deps = TEST_DEPS,
)

onos_app (
    app_name = "org.onosproject.hostprobingprovider",
    title = "Host Probing Provider",
    category = "Provider",
    url = "http://onosproject.org",
    description = "Provides host probing mechanism that discovers or verifies the existence of a host at specific location",
)
