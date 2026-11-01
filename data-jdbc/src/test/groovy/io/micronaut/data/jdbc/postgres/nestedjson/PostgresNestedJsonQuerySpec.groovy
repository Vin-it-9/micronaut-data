package io.micronaut.data.jdbc.postgres.nestedjson

import io.micronaut.context.ApplicationContext
import io.micronaut.data.annotation.Join
import io.micronaut.data.annotation.QueryResult
import io.micronaut.data.jdbc.annotation.JdbcRepository
import io.micronaut.data.jdbc.nestedjson.NestedJsonAddress
import io.micronaut.data.jdbc.nestedjson.NestedJsonChild
import io.micronaut.data.jdbc.nestedjson.NestedJsonParent
import io.micronaut.data.jdbc.nestedjson.NestedJsonTag
import io.micronaut.data.jdbc.postgres.PostgresTestPropertyProvider
import io.micronaut.data.model.query.builder.sql.Dialect
import io.micronaut.data.repository.CrudRepository
import spock.lang.AutoCleanup
import spock.lang.Shared
import spock.lang.Specification

class PostgresNestedJsonQuerySpec extends Specification implements PostgresTestPropertyProvider {

    @Shared
    @AutoCleanup
    ApplicationContext applicationContext = ApplicationContext.run(getProperties())

    @Shared
    NestedJsonParentRepository parentRepository = applicationContext.getBean(NestedJsonParentRepository)

    @Shared
    NestedJsonAddressRepository addressRepository = applicationContext.getBean(NestedJsonAddressRepository)

    @Shared
    NestedJsonChildRepository childRepository = applicationContext.getBean(NestedJsonChildRepository)

    @Shared
    NestedJsonTagRepository tagRepository = applicationContext.getBean(NestedJsonTagRepository)

    @Override
    List<String> packages() {
        ["io.micronaut.data.jdbc.postgres.nestedjson", "io.micronaut.data.jdbc.nestedjson"]
    }

    void "generated PostgreSQL JSON query executes and preserves independent collections"() {
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

    void "generated PostgreSQL JSON query maps null relationships and empty collections"() {
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

    void "generated PostgreSQL JSON query supports large documents"() {
        given:
        cleanupData()
        def parent = new NestedJsonParent(name: "large")
        parent.children = (1..150).collect {
            new NestedJsonChild(name: "child-${it}-" + ("x" * 50), parent: parent)
        }
        parentRepository.save(parent)

        when:
        def result = parentRepository.findById(parent.id).get()

        then:
        result.children.size() == 150
        result.children.every { it.name.startsWith("child-") }

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

@JdbcRepository(dialect = Dialect.POSTGRES)
interface NestedJsonParentRepository extends CrudRepository<NestedJsonParent, Long> {

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
    @QueryResult(type = QueryResult.Type.JSON)
    Optional<NestedJsonParent> findById(Long id)
}

@JdbcRepository(dialect = Dialect.POSTGRES)
interface NestedJsonAddressRepository extends CrudRepository<NestedJsonAddress, Long> {
}

@JdbcRepository(dialect = Dialect.POSTGRES)
interface NestedJsonChildRepository extends CrudRepository<NestedJsonChild, Long> {
}

@JdbcRepository(dialect = Dialect.POSTGRES)
interface NestedJsonTagRepository extends CrudRepository<NestedJsonTag, Long> {
}

