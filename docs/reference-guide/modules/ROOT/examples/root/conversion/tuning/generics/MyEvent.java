package root.conversion.tuning.generics;

import com.fasterxml.jackson.annotation.JsonTypeInfo;

import java.util.List;

// tag::json-type-info-field[]
public class MyEvent {
    @JsonTypeInfo(use = JsonTypeInfo.Id.CLASS)
    private List<Item> items;
    // constructors, getters, setters...
}
// end::json-type-info-field[]

class Item {
}
