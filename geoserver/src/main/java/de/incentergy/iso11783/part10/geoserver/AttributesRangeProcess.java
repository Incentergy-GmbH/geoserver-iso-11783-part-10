package de.incentergy.iso11783.part10.geoserver;

import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureIterator;
import org.geotools.process.factory.DescribeParameter;
import org.geotools.process.factory.DescribeProcess;
import org.geotools.process.factory.DescribeResult;
import org.geotools.api.feature.simple.SimpleFeature;
import org.geotools.api.feature.simple.SimpleFeatureType;
import org.geotools.api.feature.type.AttributeDescriptor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.geoserver.wps.gs.GeoServerProcess;

@DescribeProcess(title="AttributesRange", description="Calculate min and max values of each attribute in a feature collection")
public class AttributesRangeProcess implements GeoServerProcess {

    public static final class Range {
        private double min;
        private double max;
        private boolean isEmpty = true;

        public void extend(Number n) {
            double value = n.doubleValue();
            if (isEmpty) {
                min = max = value;
                isEmpty = false;
                return;
            }

            if (value < min) {
                min = value;
            }

            if (value > max) {
                max = value;
            }
        }

        public double getMin() {
            return min;
        }
        public double getMax() {
            return max;
        }
    }

    public static final class Results {
        public Map<String, Range> attributeRanges = new HashMap<>();

        public void addValue(String attrName, Number attrValue) {
            if (attrValue == null) {
                return;
            }
            if (!attributeRanges.containsKey(attrName)) {
                attributeRanges.put(attrName, new Range());
            }

            attributeRanges.get(attrName).extend(attrValue);
        }
    }

    @DescribeResult(name="result", description="Min and max values for each attribute")
    public Results execute(
        @DescribeParameter(name="features", description="Source feature collection") SimpleFeatureCollection features
    ) {
        SimpleFeatureType featureType = features.getSchema();
        Results result = new Results();

        List<String> numericalAttributeNames = featureType.getAttributeDescriptors().stream()
            .filter(attrDescriptor -> Number.class.isAssignableFrom(attrDescriptor.getType().getBinding()))
            .map(attrDescription -> attrDescription.getLocalName())
            .collect(Collectors.toList());

        try ( SimpleFeatureIterator iterator = features.features() ){
            while( iterator.hasNext() ){
                SimpleFeature feature = iterator.next();
                for (String attrName: numericalAttributeNames) {
                    result.addValue(attrName, (Number) feature.getAttribute(attrName));
                }
            }
        }
        return result;
    }
}