package de.incentergy.iso11783.part10.geoserver;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import org.geoserver.wps.ppio.CDataPPIO;

import net.sf.json.JSONObject;

public class AttributesRangeProcessJSONPPIO extends CDataPPIO {

    protected AttributesRangeProcessJSONPPIO() {
        super(AttributesRangeProcess.Results.class, AttributesRangeProcess.Results.class, "application/json");
    }

    @Override
    public void encode(Object value, OutputStream output) throws Exception {
        AttributesRangeProcess.Results processResult = (AttributesRangeProcess.Results) value;
        Map<String, Object> json = new HashMap<>();

        for (String attrName: processResult.attributeRanges.keySet()) {
            AttributesRangeProcess.Range range = processResult.attributeRanges.get(attrName);
            double[] minMax = {range.getMin(), range.getMax()};
            json.put(attrName, minMax);
        }

        output.write(JSONObject.fromObject(json).toString().getBytes());
    }

    @Override
    public Object decode(InputStream input) throws Exception {
        throw new UnsupportedOperationException("JSON parsing is not supported");
    }

    @Override
    public Object decode(String input) throws Exception {
        throw new UnsupportedOperationException("JSON parsing is not supported");
    }

    @Override
    public final String getFileExtension() {
        return "json";
    }
}
