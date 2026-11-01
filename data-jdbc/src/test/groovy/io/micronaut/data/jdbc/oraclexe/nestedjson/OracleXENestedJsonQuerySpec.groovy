package io.micronaut.data.jdbc.oraclexe.nestedjson

import io.micronaut.context.ApplicationContext
import io.micronaut.data.annotation.Join
import io.micronaut.data.annotation.QueryResult
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.jdbc.nestedjson.NestedJsonAddress
import io.micronaut.data.jdbc.nestedjson.NestedJsonParent
import io.micronaut.data.jdbc.nestedjson.NestedJsonChild
import io.micronaut.data.jdbc.nestedjson.NestedJsonTag
import io.micronaut.data.jdbc.oraclexe.OracleTestPropertyProvider
import io.micronaut.data.model.JsonDataType
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class OracleXENestedJsonQuerySpec extends Specification implements OracleTestPropertyProvider {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run(getProperties())

    @Shared
    NestedJsonParentOracleRepository parentRepository = applicationContext.getBean(NestedJsonParentOracleRepository)

    @Shared
    NestedJsonParentStringOracleRepository parentStringRepository = applicationContext.getBean(NestedJsonParentStringOracleRepository)

    @Shared
    NestedJsonParentBlobOracleRepository parentBlobRepository = applicationContext.getBean(NestedJsonParentBlobOracleRepository)

    @Shared
    NestedJsonAddressOracleRepository addressRepository = applicationContext.getBean(NestedJsonAddressOracleRepository)

    @Shared
    NestedJsonChildOracleRepository childRepository = applicationContext.getBean(NestedJsonChildOracleRepository)

    @Shared
    NestedJsonTagOracleRepository tagRepository = applicationContext.getBean(NestedJsonTagOracleRepository)

    @Override
    List<String> packages() {
        ["io.micronaut.data.jdbc.oraclexe.nestedjson", "io.micronaut.data.jdbc.nestedjson"]
    }

    void "generated Oracle JSON query executes and preserves independent collections"() {
        given:
        cleanupData()
        def address = addressRepository.save(new NestedJsonAddress(city: "Berlin"))
        def parent = new NestedJsonParent(name: "populated", address: address)
        parent.children = [
            new NestedJsonChild(name: "child-a", parent: parent),
            new NestedJsonChild(name: null, parent: parent)
        ]
        parent.tags = [
            new NestedJsonTag(name: "tag-a", parent: parent),
            new NestedJsonTag(name: "tag-b", parent: parent)
        ]
        parentRepository.save(parent)

        when:
        def result = parentRepository.findAll().find { it.name == "populated" }

        then:
        result != null
        result.postLoadCount == 1
        result.address.city == "Berlin"
        result.children*.name as Set == ["child-a", null] as Set
        result.tags*.name as Set == ["tag-a", "tag-b"] as Set
        result.children.size() == 2
        result.tags.size() == 2

        cleanup:
        cleanupData()
    }

    void "generated Oracle JSON query maps null relationships and empty collections"() {
        given:
        cleanupData()
        parentRepository.save(new NestedJsonParent(name: "empty"))

        when:
        def result = parentRepository.findAll().find { it.name == "empty" }

        then:
        result != null
        result.postLoadCount == 1
        result.address == null
        result.children != null
        result.children.empty
        result.tags != null
        result.tags.empty

        cleanup:
        cleanupData()
    }

    void "generated Oracle JSON query keeps large documents for native and text result types"() {
        given:
        cleanupData()
        def parent = new NestedJsonParent(name: "large")
        parent.children = (1..150).collect {
            new NestedJsonChild(name: "child-${it}-" + ("x" * 50), parent: parent)
        }
        parentRepository.save(parent)

        when:
        def defaultResult = parentRepository.findById(parent.id).get()
        def stringResult = parentStringRepository.findById(parent.id).get()
        def blobResult = parentBlobRepository.findById(parent.id).get()

        then:
        defaultResult.children.size() == 150
        stringResult.children.size() == 150
        blobResult.children.size() == 150

        cleanup:
        cleanupData()
    }

    private void cleanupData() {
        childRepository.deleteAll()
        tagRepository.deleteAll()
        parentRepository.deleteAll()
        addressRepository.deleteAll()
    }
}

@JdbcRepository(dialect = Dialect.ORACLE)
interface NestedJsonParentOracleRepository extends CrudRepository<NestedJsonParent, Long> {

    @Override
    @Join(value = "children", type = Join.Type.LEFT_FETCH)
    @Join(value = "tags", type = Join.Type.LEFT_FETCH)
    @Join(value = "address", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON)
    List<NestedJsonParent> findAll()

    @Override
    @Join(value = "children", type = Join.Type.LEFT_FETCH)
    @Join(value = "tags", type = Join.Type.LEFT_FETCH)
    @Join(value = "address", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON, jsonDataType = JsonDataType.DEFAULT)
    Optional<NestedJsonParent> findById(Long id)
}

@JdbcRepository(dialect = Dialect.ORACLE)
interface NestedJsonAddressOracleRepository extends CrudRepository<NestedJsonAddress, Long> {
}

@JdbcRepository(dialect = Dialect.ORACLE)
interface NestedJsonChildOracleRepository extends CrudRepository<NestedJsonChild, Long> {
}

@JdbcRepository(dialect = Dialect.ORACLE)
interface NestedJsonTagOracleRepository extends CrudRepository<NestedJsonTag, Long> {
}

@JdbcRepository(dialect = Dialect.ORACLE)
interface NestedJsonParentStringOracleRepository extends CrudRepository<NestedJsonParent, Long> {

    @Override
    @Join(value = "children", type = Join.Type.LEFT_FETCH)
    @Join(value = "tags", type = Join.Type.LEFT_FETCH)
    @Join(value = "address", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON, jsonDataType = JsonDataType.STRING)
    Optional<NestedJsonParent> findById(Long id)
}

@JdbcRepository(dialect = Dialect.ORACLE)
interface NestedJsonParentBlobOracleRepository extends CrudRepository<NestedJsonParent, Long> {

    @Override
    @Join(value = "children", type = Join.Type.LEFT_FETCH)
    @Join(value = "tags", type = Join.Type.LEFT_FETCH)
    @Join(value = "address", type = Join.Type.LEFT_FETCH)
    @QueryResult(type = QueryResult.Type.JSON, jsonDataType = JsonDataType.BLOB)
    Optional<NestedJsonParent> findById(Long id)
}
