package au.org.ala.resultsCache

import grails.converters.JSON
import org.codehaus.groovy.grails.commons.ConfigurationHolder

class ResultsController {

    static allowedMethods = [submit:'POST']

    def index() { }
    
    static cache = [:]

    /**
     * Store the supplied results list with a generated key.
     *
     * @param post body is a map: property 'list' holds the results
     * @key optional key to use - this is intended for development only
     * @return the generated key
     */
    def submit = {
        def bodyText = request.reader.text
        def body = JSON.parse(bodyText as String)
        def key = body.key ?: body.query.encodeAsMD5()
        cache.put key, [list: body.list, query: body.query, queryDescription: body.queryDescription, time: new Date()]
        def result = [key: key]
        render result as JSON
    }

    /**
     * Retrieve the raw stored results for the supplied key.
     *
     * @param key
     * @return
     */
    def getResults(String key) {
        def results = cache[key]
        if (results) {
            render results as JSON
        }
        else {
            def error = [error: "no results with key = ${key}"]
            render error as JSON
        }
    }
    
    /**
     * Returns a paginated list of the stored results.
     *
     * @param key the identifier of the results set in the cache
     * @param start the pagination index of the first item to display (optional defaults to 0)
     * @param pageSize the number of items to display per page (optional defaults to 10)
     * @param sortBy the property to sort on (optional)
     * @param sortOrder normal or reverse (optional defaults to normal)
     * @param facets a comma separated list of facets to include (optional)
     * @param includeFacetMembers if present the property specified of each item in a facet will be included
     *  as a list (optional)
     * @param returnAll overrides pagination if true (optional)
     * @param noResults excludes the results list if true (optional)
     * @param taxonHierarchy returns a breakdown by family and genus if true (optional)
     *
     * @return a json representation of the page of results
     */
    def getPage(String key) {
        def query = cache[key]?.query
        def queryDescription = cache[key]?.queryDescription
        def list = cache[key]?.list
        if (list) {
            def speciesList = list.results?.clone()

            // calculate any facets
            def facets = []
            params.facets?.tokenize(',')?.each {
                facets << facetFor(speciesList, it, params.includeFacetMembers)
            }

            // calculate the hierarchy
            def hierarchy = []
            if (params.taxonHierarchy) {
                hierarchy = buildFamilyHierarchy(list)
            }

            // sort the list
            if (params.sortBy && speciesList.any{it[params.sortBy]}) {
                speciesList.sort {it[params.sortBy]}
                if (params.sortOrder == 'reverse') {
                    speciesList = speciesList.reverse()
                }
            }
            // paginate the list
            if (!params.returnAll == 'true') {
                def start = params.start ? params.start as int : 0
                def pageSize = params.pageSize ? params.pageSize as int : 10
                int size = speciesList.size()
                if (size > pageSize) {
                    speciesList = speciesList[start..Math.min(start+pageSize,size)-1]
                }
            }

            // return the json
            def results = [queryDescription: queryDescription, query: query]
            if (!params.noResults) {
                results.list = speciesList
            }
            if (facets) {
                results.facetResults = facets
            }
            if (hierarchy) {
                results.taxonHierarchy = hierarchy
            }
            render results as JSON
        }
        else {
            def error = [error: "no results with key = ${key}"]
            render error as JSON
        }
    }

    /**
     * Build the facet data for the specified facet.
     *
     * @param list the list to facet
     * @param facetName the property to facet
     * @param includeFacetMembers if present the property specified of each item in a facet will be included
     *  as a list (optional)
     * @return map representing the facet values
     */
    def facetFor(list, facetName, includeFacetMembers) {
        def clone = list.clone()  // because unique mutates list
        // list of items with unique facet values 
        def uniqueFacets = clone.unique { it[facetName] }
        // list of just the facet values
        def facetValues = uniqueFacets.collect {it[facetName]}
        // add the details
        def facets = [fieldName: facetName, fieldResult: []]
        facetValues.each { facetValue ->
            // get the count - number of times the facet value occurs
            def count = list.count {it[facetName] == facetValue }
            def facetInstance = [count: count, label: facetValue]
            if (includeFacetMembers) {
                // add the list of the specified property of the members
                facetInstance.members = list.findAll({it[facetName] == facetValue}).collect{it[includeFacetMembers]}
            }
            // add to the list of facet values
            facets.fieldResult << facetInstance
        }
        // sort by descending count
        facets.fieldResult.sort{-it.count}
        return facets
    }

    def familyHierarchy(list) {
        def results = []
        
        // find unique families
        def families = list.clone().unique({it.family}).collect {it.family}
        //println "size = " + families.size()
        
        // for each family
        families.each { familyName ->
            def genera = []
            def genusRecords = list.findAll {it.family == familyName}
            // find unique genera
            def genusNames = genusRecords.unique({it.genus}).collect() {it.genus}
            // for each genus
            genusNames.each { genusName ->
                def species = list.findAll {it.genus == genusName}
                genera << [name: genusName, species: species]
            }
            results << [name: familyName, genera: genera.sort {it.name}]
        }
        
        return results
    }

    def buildFamilyHierarchy(list) {
        def results = []
        
        // get unique families
        def families = list.families
        //println "size = " + families.size()

        // for each family
        families.each { name, data ->
            def genera = []
            def genusRecords = list.results.findAll {it.family == name}
            // find unique genera
            def genusNames = genusRecords.unique({it.genus}).collect() {it.genus}
            // for each genus
            genusNames.each { genusName ->
                def species = list.results.findAll {it.genus == genusName}
                genera << [name: genusName, speciesCount: species.size(), guid: species[0].genusGuid]
            }
            results << [name: name, guid: data.guid, common: data.common, image: data.image,
                    caabCode: data.caabCode, genera: genera.sort {it.name}]
        }

        return results
    }

    def dumpCache = {
        def results = [totalEntries: cache.size(), keys: []]
        results.keys = cache.keySet().collect {
            [key:  it, query: cache[it].query, time: cache[it].time,
             view: ConfigurationHolder.config.grails.serverURL + "/results/getResults?key=" + it]
        }
        if (params.key) {
            def data = cache[params.key]
            if (data) {
                results[params.key] = data
            }
        }
        results.keys = results.keys.sort({it.time}).reverse()
        render results as JSON
    }
}