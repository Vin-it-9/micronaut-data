package io.micronaut.data.jdbc.nestedjson

import io.micronaut.core.annotation.Introspected
import io.micronaut.data.annotation.GeneratedValue
import io.micronaut.data.annotation.Id
import io.micronaut.data.annotation.MappedEntity
import io.micronaut.data.annotation.Relation
import jakarta.persistence.PostLoad
import jakarta.persistence.Transient

@Introspected
@MappedEntity("nested_json_parent")
class NestedJsonParent {
    @Id
    @GeneratedValue
    Long id

    String name

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent", cascade = Relation.Cascade.ALL)
    List<NestedJsonChild> children = []

    @Relation(value = Relation.Kind.ONE_TO_MANY, mappedBy = "parent", cascade = Relation.Cascade.ALL)
    List<NestedJsonTag> tags = []

    @Relation(Relation.Kind.MANY_TO_ONE)
    NestedJsonAddress address

    @Transient
    int postLoadCount

    @PostLoad
    void onPostLoad() {
        postLoadCount++
    }
}

@Introspected
@MappedEntity("nested_json_child")
class NestedJsonChild {
    @Id
    @GeneratedValue
    Long id

    String name

    @Relation(Relation.Kind.MANY_TO_ONE)
    NestedJsonParent parent
}

@Introspected
@MappedEntity("nested_json_tag")
class NestedJsonTag {
    @Id
    @GeneratedValue
    Long id

    String name

    @Relation(Relation.Kind.MANY_TO_ONE)
    NestedJsonParent parent
}

@Introspected
@MappedEntity("nested_json_address")
class NestedJsonAddress {
    @Id
    @GeneratedValue
    Long id

    String city
}

