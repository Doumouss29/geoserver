/* (c) 2019 Open Source Geospatial Foundation - all rights reserved
 * This code is licensed under the GPL 2.0 license, available at the root
 * application directory.
 */
package org.geoserver.api.images;

import static org.geotools.gce.imagemosaic.Utils.FF;

import net.opengis.wfs20.Wfs20Factory;

import org.apache.commons.io.IOUtils;
import org.geoserver.api.APIDispatcher;
import org.geoserver.api.APIException;
import org.geoserver.api.APIRequestInfo;
import org.geoserver.api.APIService;
import org.geoserver.api.ConformanceDocument;
import org.geoserver.api.HTMLResponseBody;
import org.geoserver.api.OpenAPIMessageConverter;
import org.geoserver.catalog.CoverageInfo;
import org.geoserver.config.GeoServer;
import org.geoserver.data.DimensionFilterBuilder;
import org.geoserver.ows.URLMangler.URLType;
import org.geoserver.ows.kvp.TimeParser;
import org.geoserver.ows.util.ResponseUtils;
import org.geoserver.platform.ServiceException;
import org.geoserver.wfs.request.FeatureCollectionResponse;
import org.geoserver.wfs.request.GetFeatureRequest;
import org.geotools.coverage.grid.io.DimensionDescriptor;
import org.geotools.coverage.grid.io.GranuleSource;
import org.geotools.coverage.grid.io.StructuredGridCoverage2DReader;
import org.geotools.data.DataUtilities;
import org.geotools.data.FileGroupProvider;
import org.geotools.data.Query;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.transform.Definition;
import org.geotools.data.transform.TransformFeatureSource;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.util.factory.Hints;
import org.geotools.util.logging.Logging;
import org.opengis.feature.Feature;
import org.opengis.filter.Filter;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.operation.TransformException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.text.ParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.servlet.http.HttpServletResponse;

import io.swagger.v3.oas.models.OpenAPI;

/** A service to manage collections of images (mosaics, in GeoServer) */
@APIService(
        service = "Images",
        version = "1.0",
        landingPage = "ogc/images",
        serviceClass = ImagesServiceInfo.class)
@RequestMapping(path = APIDispatcher.ROOT_PATH + "/images")
public class ImagesService {

    static final Logger LOGGER = Logging.getLogger(ImagesService.class);

    static final String CORE = "http://www.opengis.net/spec/ogcapi-common-1/1.0/req/core";
    static final String COLLECTIONS =
            "http://www.opengis.net/spec/ogcapi-common-1/1.0/req/collections";
    static final String IMAGES_CORE = "http://www.opengis.net/spec/ogcapi-images-1/1.0/req/core";
    static final String IMAGES_TRANSACTIONAL =
            "http://www.opengis.net/spec/ogcapi-images-1/1.0/req/transactional";

    public static String IMAGE_ID = "OGCImages:ImageId";
    public static String COLLECTION_ID = "OGCImages:CollectionId";

    private final GeoServer geoServer;
    private final AssetHasher assetHasher;

    // this could be done in an argument resolver returning a Filter, for example, however
    // each protocol would need a different thing, so kept the KVP parser as a way to have
    // private logic here
    private ImagesBBoxKvpParser bboxParser = new ImagesBBoxKvpParser();
    private TimeParser timeParser = new TimeParser();

    public ImagesService(GeoServer geoServer, AssetHasher assetHasher) {
        this.geoServer = geoServer;
        this.assetHasher = assetHasher;
    }

    @GetMapping(name = "getLandingPage")
    @ResponseBody
    @HTMLResponseBody(templateName = "landingPage.ftl", fileName = "landingPage.html")
    public ImagesLandingPage getLandingPage() {
        ImagesServiceInfo service = getService();
        return new ImagesLandingPage(
                (service.getTitle() == null) ? "Images server" : service.getTitle(),
                (service.getAbstract() == null) ? "" : service.getAbstract());
    }

    public ImagesServiceInfo getService() {
        return geoServer.getService(ImagesServiceInfo.class);
    }

    @GetMapping(path = "conformance", name = "getConformanceDeclaration")
    @ResponseBody
    public ConformanceDocument conformance() {
        List<String> classes = Arrays.asList(CORE, COLLECTIONS, IMAGES_CORE, IMAGES_TRANSACTIONAL);
        return new ConformanceDocument(classes);
    }

    @GetMapping(
            path = "api",
            name = "getApi",
            produces = {
                OpenAPIMessageConverter.OPEN_API_VALUE,
                "application/x-yaml",
                MediaType.TEXT_XML_VALUE
            })
    @ResponseBody
    @HTMLResponseBody(templateName = "api.ftl", fileName = "api.html")
    public OpenAPI api() {
        return new ImagesAPIBuilder(geoServer).build(getService());
    }

    @GetMapping(path = "collections", name = "getCollections")
    @ResponseBody
    @HTMLResponseBody(templateName = "collections.ftl", fileName = "collections.html")
    public ImagesCollectionsDocument getCollections() {
        return new ImagesCollectionsDocument(geoServer);
    }

    @GetMapping(path = "collections/{collectionId}", name = "describeCollection")
    @ResponseBody
    @HTMLResponseBody(templateName = "collection.ftl", fileName = "collection.html")
    public ImagesCollectionDocument collection(
            @PathVariable(name = "collectionId") String collectionId)
            throws FactoryException, TransformException, IOException {
        CoverageInfo coverage = getStructuredCoverageInfo(collectionId);
        ImagesCollectionDocument collection = new ImagesCollectionDocument(coverage, false);

        return collection;
    }

    private CoverageInfo getStructuredCoverageInfo(String collectionId) throws IOException {
        CoverageInfo coverageInfo = geoServer.getCatalog().getCoverageByName(collectionId);
        if (coverageInfo != null
                && coverageInfo.getGridCoverageReader(null, null)
                        instanceof StructuredGridCoverage2DReader) {
            return coverageInfo;
        }

        throw new APIException(
                "NotFound", "Could not locate " + collectionId, HttpStatus.NOT_FOUND);
    }

    @GetMapping(path = "collections/{collectionId}/images", name = "getImages")
    @ResponseBody
    @HTMLResponseBody(templateName = "images.ftl", fileName = "images.html")
    public ImagesResponse images(
            @PathVariable(name = "collectionId") String collectionId,
            @RequestParam(name = "startIndex", required = false, defaultValue = "0") int startIndex,
            @RequestParam(name = "limit", required = false) Integer limit,
            @RequestParam(name = "bbox", required = false) String bbox,
            @RequestParam(name = "time", required = false) String time,
            String imageId)
            throws Exception {
        CoverageInfo coverage = getStructuredCoverageInfo(collectionId);
        StructuredGridCoverage2DReader reader =
                (StructuredGridCoverage2DReader) coverage.getGridCoverageReader(null, null);

        // build filters
        List<Filter> filters = new ArrayList<>();
        if (bbox != null) {
            filters.add(buildBBOXFilter(bbox));
        }
        String nativeName = coverage.getNativeCoverageName();
        if (time != null) {
            List<DimensionDescriptor> descriptors = reader.getDimensionDescriptors(nativeName);
            Optional<DimensionDescriptor> timeDescriptor =
                    descriptors.stream()
                            .filter(dd -> "time".equalsIgnoreCase(dd.getName()))
                            .findFirst();
            if (!timeDescriptor.isPresent()) {
                throw new APIException(
                        "InvalidParameter",
                        "Time not supported for this image collection",
                        HttpStatus.BAD_REQUEST);
            }
            filters.add(buildTimeFilter(timeDescriptor.get(), time));
        }
        if (imageId != null) {
            filters.add(FF.id(FF.featureId(imageId)));
        }
        Filter filter = mergeFiltersAnd(filters);

        // run it
        GranuleSource granuleSource = reader.getGranules(nativeName, true);
        org.geotools.data.Query gtQuery = new org.geotools.data.Query(nativeName, filter);
        gtQuery.setStartIndex(startIndex);
        if (limit == null) {
            limit = getService().getMaxImages();
        }
        int maxFeatures = limit.intValue();
        gtQuery.setMaxFeatures(maxFeatures);
        gtQuery.setHints(new Hints(GranuleSource.FILE_VIEW, true));
        SimpleFeatureCollection granules = granuleSource.getGranules(gtQuery);

        // if single image is not found, throw a 400
        if (imageId != null && granules.isEmpty()) {
            throw new APIException(
                    "NotFound",
                    "Image with id "
                            + imageId
                            + " could not be found in collection "
                            + collectionId,
                    HttpStatus.NOT_FOUND);
        }

        // transforms granule source attribute names
        SimpleFeatureCollection remapped =
                remapGranules(granules, reader.getDimensionDescriptors(nativeName));

        return wrapInImageResponse(
                coverage, filter, startIndex, limit, bbox, time, imageId, remapped);
    }

    @GetMapping(path = "collections/{collectionId}/images/{imageId:.+}", name = "getImage")
    @ResponseBody
    @HTMLResponseBody(templateName = "image.ftl", fileName = "image.html")
    public ImagesResponse image(
            @PathVariable(name = "collectionId") String collectionId,
            @PathVariable(name = "imageId") String imageId)
            throws Exception {
        return images(collectionId, 0, null, null, null, imageId);
    }

    @GetMapping(
            path = "collections/{collectionId}/images/{imageId:.+}/assets/{assetId:.+}",
            name = "getAsset")
    public void asset(
            @PathVariable(name = "collectionId") String collectionId,
            @PathVariable(name = "imageId") String imageId,
            @PathVariable(name = "assetId") String assetId,
            HttpServletResponse response)
            throws Exception {
        ImagesResponse ir = images(collectionId, 0, null, null, null, imageId);
        SimpleFeatureCollection granules =
                (SimpleFeatureCollection) ir.getResponse().getFeatures().get(0);
        Feature granule = DataUtilities.first(granules);
        Object fileGroupCandidate = granule.getUserData().get(GranuleSource.FILES);
        if (!(fileGroupCandidate instanceof FileGroupProvider.FileGroup)) {
            throw new APIException(
                    "NotFound",
                    "Could not find assets for image " + imageId + " in collection " + collectionId,
                    HttpStatus.NOT_FOUND);
        }

        // look for the right file
        FileGroupProvider.FileGroup files = (FileGroupProvider.FileGroup) fileGroupCandidate;
        Optional<File> asset = Optional.empty();
        if (assetHasher.matches(files.getMainFile(), assetId)) {
            asset = Optional.of(files.getMainFile());
        } else {
            asset =
                    files.getSupportFiles().stream()
                            .filter(f -> assetHasher.matches(f, assetId))
                            .findFirst();
        }
        if (!asset.isPresent()) {
            throw new APIException(
                    "NotFound",
                    "Cannot find asset with id "
                            + assetId
                            + " in image  "
                            + imageId
                            + " in collection "
                            + collectionId,
                    HttpStatus.NOT_FOUND);
        }
        response.setHeader(HttpHeaders.CONTENT_TYPE, assetHasher.guessMimeType(asset.get()));
        response.setStatus(HttpStatus.OK.value());
        try (FileInputStream fis = new FileInputStream(asset.get())) {
            IOUtils.copy(fis, response.getOutputStream());
        }
    }

    private SimpleFeatureCollection remapGranules(
            SimpleFeatureCollection granules, List<DimensionDescriptor> dimensionDescriptors)
            throws IOException {
        // remap the time attribute to "datetime", if needed
        DimensionDescriptor timeDescriptor =
                dimensionDescriptors.stream()
                        .filter(dd -> "time".equalsIgnoreCase(dd.getName()))
                        .findFirst()
                        .get();
        String timeAttributeName = timeDescriptor.getStartAttribute();
        if (timeAttributeName.equals("datetime")) {
            return granules;
        }

        // here we could apply configured remappings to well known sets of attributes,
        // like myCCAttribute -> eo:cloudCover for example

        // remapping requires a FeatureSource, no problem
        List<Definition> definitions =
                granules.getSchema().getAttributeDescriptors().stream()
                        .map(
                                d -> {
                                    String outputName = d.getLocalName();
                                    if (d.getLocalName().equals(timeAttributeName)) {
                                        outputName = "datetime";
                                    }
                                    return new Definition(
                                            outputName,
                                            FF.property(d.getLocalName()),
                                            d.getType().getBinding());
                                })
                        .collect(Collectors.toList());
        SimpleFeatureSource granulesSource = DataUtilities.source(granules);
        TransformFeatureSource remappedSource =
                new TransformFeatureSource(
                        granulesSource, granules.getSchema().getName(), definitions);
        return remappedSource.getFeatures(Query.ALL);
    }

    public ImagesResponse wrapInImageResponse(
            CoverageInfo coverage,
            Filter filter,
            int startIndex,
            int maxFeatures,
            String bbox,
            String time,
            String imageId,
            SimpleFeatureCollection granules) {
        // build the request in a way core WFS machinery can understand it
        GetFeatureRequest request =
                GetFeatureRequest.adapt(Wfs20Factory.eINSTANCE.createGetFeatureType());

        // store information about single vs multi request
        RequestAttributes requestAttributes = RequestContextHolder.getRequestAttributes();
        if (requestAttributes != null) {
            requestAttributes.setAttribute(IMAGE_ID, imageId, RequestAttributes.SCOPE_REQUEST);
            requestAttributes.setAttribute(
                    COLLECTION_ID, coverage.prefixedName(), RequestAttributes.SCOPE_REQUEST);
        }

        // build a response compatible with the GeoJSON encoding machinery
        FeatureCollectionResponse result = request.createResponse();
        int count = granules.size();
        result.setNumberOfFeatures(BigInteger.valueOf(count));
        // result.setTotalNumberOfFeatures(total); TODO: add this back
        result.setTimeStamp(Calendar.getInstance());
        result.getFeature().add(granules);
        result.setGetFeatureById(imageId != null);

        String imagesPath =
                "ogc/images/collections/"
                        + ResponseUtils.urlEncode(coverage.prefixedName())
                        + "/images";

        // copy over the request parameters, removing the paging ones
        Map<String, String> kvp =
                APIRequestInfo.get().getRequest().getParameterMap().entrySet().stream()
                        .collect(
                                Collectors.toMap(
                                        e -> e.getKey(),
                                        e -> e.getValue() != null ? e.getValue()[0] : null));
        kvp.remove("startIndex");
        kvp.remove("limit");

        // build prev link if needed
        if (startIndex > 0) {
            // previous offset calculated as the current offset - maxFeatures, or 0 if this is a
            // negative value, while  previous count should be current offset - previousOffset
            int prevOffset = Math.max(startIndex - maxFeatures, 0);
            kvp.put("startIndex", String.valueOf(prevOffset));
            kvp.put("limit", String.valueOf(startIndex - prevOffset));
            result.setPrevious(buildURL(imagesPath, kvp));
        }

        // build next link if needed
        if (count > 0 && maxFeatures > -1 && maxFeatures <= count) {
            kvp.put("startIndex", String.valueOf(maxFeatures > 0 ? maxFeatures + count : count));
            kvp.put("limit", String.valueOf(maxFeatures));
            result.setNext(buildURL(imagesPath, kvp));
        }

        // build a response tracking both results and request to allow reusing the existing WFS
        // output formats
        return new ImagesResponse(request.getAdaptee(), result);
    }

    private String buildURL(String itemsPath, Map<String, String> kvp) {
        return ResponseUtils.buildURL(
                APIRequestInfo.get().getBaseURL(), itemsPath, kvp, URLType.SERVICE);
    }

    private Filter buildTimeFilter(DimensionDescriptor descriptor, String time)
            throws ParseException, IOException {
        List times = new ArrayList(timeParser.parse(time));
        if (times.isEmpty() || times.size() > 1) {
            throw new ServiceException(
                    "Invalid time specification, must be a single time, or a time range",
                    ServiceException.INVALID_PARAMETER_VALUE,
                    "time");
        }

        List<Filter> filters = new ArrayList<>();
        Object timeSpec = times.iterator().next();
        DimensionFilterBuilder filterBuilder = new DimensionFilterBuilder(FF);
        filterBuilder.appendFilters(
                descriptor.getStartAttribute(), descriptor.getEndAttribute(), times);
        return filterBuilder.getFilter();
    }

    private Filter mergeFiltersAnd(List<Filter> filters) {
        if (filters.isEmpty()) {
            return Filter.INCLUDE;
        } else if (filters.size() == 1) {
            return filters.get(0);
        } else {
            return FF.and(filters);
        }
    }

    public Filter buildBBOXFilter(@RequestParam(name = "bbox", required = false) String bbox)
            throws Exception {
        Object parsed = bboxParser.parse(bbox);
        if (parsed instanceof ReferencedEnvelope) {
            return FF.bbox(FF.property(""), (ReferencedEnvelope) parsed);
        } else if (parsed instanceof ReferencedEnvelope[]) {
            List<Filter> filters =
                    Stream.of((ReferencedEnvelope[]) parsed)
                            .map(e -> FF.bbox(FF.property(""), e))
                            .collect(Collectors.toList());
            return FF.or(filters);
        } else {
            throw new IllegalArgumentException("Could not understand parsed bbox " + parsed);
        }
    }
}
