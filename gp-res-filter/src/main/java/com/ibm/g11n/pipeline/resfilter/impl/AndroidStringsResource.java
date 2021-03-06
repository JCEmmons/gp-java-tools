/*
 * Copyright IBM Corp. 2015, 2018
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.ibm.g11n.pipeline.resfilter.impl;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.BreakIterator;
import java.text.FieldPosition;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Attr;
import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import com.ibm.g11n.pipeline.resfilter.FilterOptions;
import com.ibm.g11n.pipeline.resfilter.IllegalResourceFormatException;
import com.ibm.g11n.pipeline.resfilter.LanguageBundle;
import com.ibm.g11n.pipeline.resfilter.ResourceFilter;
import com.ibm.g11n.pipeline.resfilter.ResourceFilterException;
import com.ibm.g11n.pipeline.resfilter.ResourceString;
import com.ibm.icu.text.MessagePattern;
import com.ibm.icu.text.MessagePattern.ArgType;
import com.ibm.icu.text.MessagePattern.Part;
import com.ibm.icu.text.MessagePattern.Part.Type;

/**
 * Android string resource filter implementation.
 * 
 * @author Farhan Arshad
 */
public class AndroidStringsResource extends ResourceFilter {

    private static final String CHAR_SET = "UTF-8";

    private static final String RESOURCES_STRING = "resources";
    private static final String NAME_STRING = "name";
    private static final String STR_STRING = "string";
    private static final String STR_ARRAY = "string-array";
    private static final String PLURAL_STRING = "plural";
    private static final String PLURALS_STRING = "plurals";
    private static final String QUANTITY_STRING = "quantity";
    private static final String ITEM_STRING = "item";

    private static final String STR_ARRAY_OPEN_TAG_PTRN = "^(\\s*<string-array\\s*name=\".*\">).*";
    private static final String STR_ARRAY_CLOSE_TAG_PTRN = ".*(\\s*</string-array\\s*>)$";

    private static final String STR_OPEN_TAG_PTRN = "^(\\s*<string\\s*name=\".*\">).*";
    private static final String STR_CLOSE_TAG_PTRN = ".*(\\s*</string\\s*>)$";

    private static final String PLURALS_OPEN_TAG_PTRN = "^(\\s*<plurals\\s*name=\".*\">).*";
    private static final String PLURALS_CLOSE_TAG_PTRN = ".*(\\s*</plurals\\s*>)$";

    private static final String PLURAL_ENTIRY_PTRN = "{0}, plural, {1}";

    @Override
    public LanguageBundle parse(InputStream inStream, FilterOptions options)
            throws IOException, ResourceFilterException {

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder = null;
        try {
            builder = factory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            // runtime problem
            throw new ResourceFilterException(e);
        }

        Document document = null;
        try {
            document = builder.parse(inStream);
        } catch (SAXException e) {
            throw new IllegalResourceFormatException(e);
        }

        Element elem = document.getDocumentElement();
        NodeList nodeList = elem.getChildNodes();
        List<ResourceString> resStrings = new LinkedList<>();
        collectResourceStrings(nodeList, 1 /* the first sequence number */, resStrings);

        LanguageBundle bundle = new LanguageBundle();
        bundle.setResourceStrings(resStrings);

        return bundle;
    }

    /**
     * This method traverses through the DOM tree and collect resource strings
     *
     * @param nodeList
     *            NodeList object
     * @param startSeqNum
     *            The first sequence number to be used
     * @param resStrings
     *            Collection to store result resource strings
     * @return The last sequence number + 1
     */
    private int collectResourceStrings(NodeList nodeList, int startSeqNum, Collection<ResourceString> resStrings) {
        int seqNum = startSeqNum;
        for (int i = 0; i < nodeList.getLength(); i++) {
            Node node = nodeList.item(i);
            // looking for DOM element <string name=$NAME>VALUE</string>
            String nodeName = node.getNodeName();
            if (nodeName.equals(STR_STRING) || nodeName.equals(STR_ARRAY)) {
                String key = node.getAttributes().getNamedItem(NAME_STRING).getNodeValue();
                String value = node.getTextContent();

                // turn into array format, i.e. [vale1, value2]
                if (nodeName.equals(STR_ARRAY)) {
                    value = "[" + value.trim().replaceAll("\\n[ \t]+", ", ") + "]";
                }

                resStrings.add(ResourceString.with(key, value).sequenceNumber(seqNum++).build());
            } else if (nodeName.equals(PLURALS_STRING)) {
                String key = node.getAttributes().getNamedItem(NAME_STRING).getNodeValue();
                String value = "";
                String plural_Str = "";
                String comments = "";

                NodeList plural_nodes = node.getChildNodes();

                for (int pi = 0; pi < plural_nodes.getLength(); pi++) {
                    Node pNode = plural_nodes.item(pi);
                    if (pNode.getNodeName().equals(ITEM_STRING)) {
                        String pKey = pNode.getAttributes().getNamedItem(QUANTITY_STRING).getNodeValue();
                        String pValue = pNode.getTextContent();
                        plural_Str += (pKey + "{" + pValue + "} ");
                    } else if (pNode.getNodeType() == Node.COMMENT_NODE) {
                        String comment = pNode.getTextContent();
                        if (comment.trim().length() > 0) {
                            comments += comment;
                        }
                    }
                }

                plural_Str = plural_Str.trim();
                MessageFormat mf_plural = new MessageFormat(PLURAL_ENTIRY_PTRN);
                StringBuffer msg_value = new StringBuffer();
                mf_plural.format(new Object[] { key, plural_Str }, msg_value, new FieldPosition(0));

                value = "{" + msg_value.toString() + "}";
                resStrings.add(ResourceString.with(key, value).sequenceNumber(seqNum++).addNote(comments).build());
            } else {
                seqNum = collectResourceStrings(node.getChildNodes(), seqNum, resStrings);
            }
        }
        return seqNum;
    }

    @Override
    public void write(OutputStream outStream, LanguageBundle languageBundle, FilterOptions options)
            throws IOException, ResourceFilterException {

        List<ResourceString> resStrings = languageBundle.getSortedResourceStrings();

        DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder docBuilder = null;
        try {
            docBuilder = docFactory.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            // runtime problem
            throw new RuntimeException(e);
        }

        // root elements
        Document doc = docBuilder.newDocument();

        // creating <resources></resources>
        Element rootElement = doc.createElement(RESOURCES_STRING);

        for (ResourceString resString : resStrings) {
            String value = resString.getValue();
            Map<String, String> plural_categories = null;

            if (value.startsWith("[") && value.endsWith("]")) {
                // creating <string-array name="$NAME">
                Element child = doc.createElement(STR_ARRAY);
                Attr attr = doc.createAttribute(NAME_STRING);
                attr.setValue(resString.getKey());
                child.setAttributeNode(attr);

                int startIndex = 0;
                int endIndex = -1;

                while (endIndex < value.length() - 1) {
                    endIndex = value.indexOf(',', startIndex);

                    if (endIndex == -1) {
                        endIndex = value.length() - 1;
                    }

                    String itemValue = value.substring(startIndex + 1, endIndex);

                    Element arrayChild = doc.createElement("item");
                    arrayChild.setTextContent(itemValue);
                    child.appendChild(arrayChild);

                    startIndex = endIndex + 1;
                }
                rootElement.appendChild(child);
            } else if (!(plural_categories = getPluralCategories(value)).isEmpty()) {
                Element child = doc.createElement(PLURALS_STRING);
                Attr attr = doc.createAttribute(NAME_STRING);
                attr.setValue(resString.getKey());
                child.setAttributeNode(attr);

                if (!resString.getNotes().isEmpty()) {
                    List<String> comments = resString.getNotes();
                    for (String comment : comments) {
                        Comment d_comment = doc.createComment(comment);
                        child.appendChild(d_comment);
                    }
                }

                /**
                 * Append plural category items Show the items in predefined
                 * order
                 */
                for (String pKey : Constants.PLURAL_CATEGORIES) {
                    if (plural_categories.containsKey(pKey)) {
                        String pValue = plural_categories.get(pKey);
                        Element item = doc.createElement(ITEM_STRING);
                        Attr pAttr = doc.createAttribute(QUANTITY_STRING);
                        pAttr.setValue(pKey);
                        item.setAttributeNode(pAttr);
                        item.setTextContent(pValue);
                        child.appendChild(item);
                    }
                }

                rootElement.appendChild(child);
            } else {
                // creating <string name=$NAME>VALUE</string>
                Element child = doc.createElement(STR_STRING);
                Attr attr = doc.createAttribute(NAME_STRING);
                attr.setValue(resString.getKey());
                child.setAttributeNode(attr);
                child.setTextContent(value);
                rootElement.appendChild(child);
            }
        }
        doc.appendChild(rootElement);

        TransformerFactory transformerFactory = TransformerFactory.newInstance();
        Transformer transformer = null;
        try {
            transformer = transformerFactory.newTransformer();
        } catch (TransformerConfigurationException e) {
            // runtime problem
            throw new ResourceFilterException(e);
        }

        // to add the tab spacing to files
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "4");

        transformer.setOutputProperty(OutputKeys.STANDALONE, "no");

        DOMSource source = new DOMSource(doc);
        StreamResult result = new StreamResult(outStream);

        // write the file
        try {
            transformer.transform(source, result);
        } catch (TransformerException e) {
            // runtime problem?
            throw new ResourceFilterException(e);
        }
    }

    @Override
    public void merge(InputStream baseStream, OutputStream outStream, LanguageBundle languageBundle,
            FilterOptions options) throws IOException, ResourceFilterException {

        // put res data into a map for easier searching
        Map<String, String> kvMap = Utils.createKeyValueMap(languageBundle.getResourceStrings());
        BreakIterator brkItr = Utils.getWordBreakIterator(options);

        BufferedReader reader = new BufferedReader(new InputStreamReader(baseStream, CHAR_SET));
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(outStream, CHAR_SET));

        String line;
        while ((line = reader.readLine()) != null) {
            if (line.matches(STR_ARRAY_OPEN_TAG_PTRN)) {
                // handle <string-array name="name"> tag
                String openingTag = line.substring(0, line.indexOf('>') + 1);
                String key = openingTag.substring(openingTag.indexOf('"') + 1, openingTag.lastIndexOf('"'));

                if (!kvMap.containsKey(key)) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String value = kvMap.get(key);

                if (!(value.startsWith("[") && value.endsWith("]"))) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String tabSubString = openingTag.substring(0, openingTag.indexOf('<'));
                String spaces = tabSubString + getTabStr(tabSubString);
                writer.write(openingTag);
                writer.newLine();

                String[] items = value.substring(1, value.length() - 1).split(",");

                for (int i = 0; i < items.length; i++) {
                    writer.write(formatMessage("<item>", items[i].trim(), "</item>", spaces, brkItr));
                }

                writer.write(openingTag.substring(0, openingTag.indexOf('<')));

                writer.write("</string-array>");
                writer.newLine();

                while ((line = reader.readLine()) != null && !line.matches(STR_ARRAY_CLOSE_TAG_PTRN))
                    ;
            } else if (line.matches(STR_OPEN_TAG_PTRN)) {
                // handle <string name="name"> tag
                String openingTag = line.substring(0, line.indexOf('>') + 1);
                String key = openingTag.substring(openingTag.indexOf('"') + 1, openingTag.lastIndexOf('"'));

                if (!kvMap.containsKey(key)) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String value = kvMap.get(key);

                String spaces = openingTag.substring(0, openingTag.indexOf('<'));

                writer.write(formatMessage(openingTag.trim(), value, "</string>", spaces, brkItr));

                while (line != null && !line.matches(STR_CLOSE_TAG_PTRN)) {
                    line = reader.readLine();
                }
            } else if (line.matches(PLURALS_OPEN_TAG_PTRN)) {
                // handle <plurals name="name"> tag
                String openingTag = line.substring(0, line.indexOf('>') + 1);
                String key = openingTag.substring(openingTag.indexOf('"') + 1, openingTag.lastIndexOf('"'));

                if (!kvMap.containsKey(key)) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String value = kvMap.get(key);

                Map<String, String> plural_categories = null;

                if ((plural_categories = getPluralCategories(value)).isEmpty()) {
                    writer.write(line);
                    writer.newLine();
                    continue;
                }

                String tabSubString = openingTag.substring(0, openingTag.indexOf('<'));
                String spaces = tabSubString + getTabStr(tabSubString);
                writer.write(openingTag);
                writer.newLine();

                for (String pKey : Constants.PLURAL_CATEGORIES) {
                    if (plural_categories.containsKey(pKey)) {
                        String pValue = plural_categories.get(pKey);
                        // <item quantity="one">
                        String itemStr = "<item quantity=\"" + pKey + "\">";
                        writer.write(formatMessage(itemStr, pValue.trim(), "</item>", spaces, brkItr));
                    }
                }
                writer.write(openingTag.substring(0, openingTag.indexOf('<')));

                writer.write("</plurals>");
                writer.newLine();

                while (line != null && !line.matches(PLURALS_CLOSE_TAG_PTRN)) {
                    line = reader.readLine();
                }
            } else {
                writer.write(line);
                writer.newLine();
            }
        }
        writer.flush();
    }

    /**
     * This method looks at the provided string to determine if a tab char or
     * spaces are being used for tabbing.
     *
     * Defaults to spaces;
     */
    static String getTabStr(String str) {
        if (!str.isEmpty() && str.charAt(0) == '\t') {
            return "\t";
        } else {
            return "    ";
        }
    }

    /**
     * Gets the number of spaces the whitespace string is using. Tab chars are
     * equal to 4 chars. i.e. a tab is considered to be of size 4.
     */
    static int getSpacesSize(String whitespace) {
        int size = 0;
        for (int i = 0; i < whitespace.length(); i++) {
            if (whitespace.charAt(i) == '\t') {
                size += 4;
            } else if (whitespace.charAt(i) == ' ') {
                size++;
            }
        }
        return size;
    }

    static String formatMessage(String openingTag, String message, String closingTag, String whitespace,
            BreakIterator brkItr) {
        int maxLineLen = 80;

        StringBuilder output = new StringBuilder();

        int messageLen = message.length();

        output.append(whitespace).append(openingTag);

        // message fits on one line
        if (maxLineLen > getSpacesSize(whitespace) + openingTag.length() + messageLen + closingTag.length()) {
            return output.append(message).append(closingTag).append('\n').toString();
        }

        // message needs to be split onto multiple lines
        output.append('\n');

        // the available char space once we account for the tabbing
        // spaces and other chars such as quotes
        int available = maxLineLen - getSpacesSize(whitespace) - 4;

        String tabStr = getTabStr(whitespace);

        // a word iterator is used to traverse the message;
        // a reference to the previous word break is kept
        // so that once the current reference goes beyond
        // the available char limit, the message can be split
        // without going over the limit
        brkItr.setText(message);
        int start = 0;
        int end = brkItr.first();
        int prevEnd = end;
        while (end != BreakIterator.DONE) {
            prevEnd = end;
            end = brkItr.next();
            if (end - start > available) {
                output.append(whitespace).append(tabStr).append(message.substring(start, prevEnd)).append('\n');
                start = prevEnd;
            } else if (end == messageLen) {
                output.append(whitespace).append(tabStr).append(message.substring(start, end)).append('\n');
            }
        }

        return output.append(whitespace).append(closingTag).append('\n').toString();
    }

    /**
     * Judge if the input string contains plural format
     */
    static boolean containPluralString(String inputString) {
        boolean result = false;

        if (inputString.indexOf(PLURAL_STRING) < 0) {
            return false;
        }

        try {
            MessagePattern msgPat = new MessagePattern(inputString);

            int numParts = msgPat.countParts();
            for (int i = 0; i < numParts; i++) {
                Part part = msgPat.getPart(i);
                if (part.getType() == Type.ARG_START && part.getArgType() == ArgType.PLURAL) {
                    return true;
                }
            }
        } catch (Exception e) {
            return false;
        }

        return result;
    }

    /**
     * Get the plural part from the input message
     */
    static String getPluralString(String inputString) {
        String pluralString = "";

        if (inputString.indexOf(PLURAL_STRING) < 0) {
            return pluralString;
        }

        try {
            MessagePattern msgPat = new MessagePattern(inputString);

            int numParts = msgPat.countParts();
            int start = -1;
            for (int i = 0; i < numParts; i++) {
                Part part = msgPat.getPart(i);
                if (part.getType() == Type.ARG_START && part.getArgType() == ArgType.PLURAL) {
                    start = part.getIndex();
                    continue;
                }
                if (part.getType() == Type.ARG_LIMIT && part.getArgType() == ArgType.PLURAL) {
                    pluralString = inputString.substring(start, part.getIndex() + 1);
                }
            }
        } catch (Exception e) {
            return pluralString;
        }

        return pluralString;
    }

    /**
     * Parse plural string and get contained categories
     */
    static Map<String, String> getPluralCategories(String inputString) {
        Map<String, String> category_map = new HashMap<>();

        if (inputString.indexOf(PLURAL_STRING) < 0) {
            return category_map;
        }

        try {
            MessagePattern msgPat = new MessagePattern(inputString);

            int numParts = msgPat.countParts();
            boolean start = false;
            String key = "";
            int mStart = -1;

            for (int i = 0; i < numParts; i++) {
                Part part = msgPat.getPart(i);
                if (part.getType() == Type.ARG_START && part.getArgType() == ArgType.PLURAL) {
                    start = true;
                    continue;
                }
                if (start && part.getType() == Type.ARG_SELECTOR) {
                    int selector = part.getIndex();
                    int len = part.getLength();
                    key = inputString.substring(selector, selector + len);
                }

                if (start && part.getType() == Type.MSG_START) {
                    mStart = part.getIndex();
                }

                if (start && part.getType() == Type.MSG_LIMIT) {
                    int mEnd = part.getIndex();
                    if (mStart > -1 && key.length() > 0) {
                        String value = inputString.substring(mStart + 1, mEnd);
                        category_map.put(key, value);
                        mStart = -1;
                        key = "";
                    }
                }

                if (part.getType() == Type.ARG_LIMIT && part.getArgType() == ArgType.PLURAL) {
                    start = false;
                }
            }
        } catch (NumberFormatException nfe) {
            // MessagePattern exception, Invalid input string
        } catch (IllegalArgumentException iae) {
            // MessagePattern exception, Invalid input string
        } catch (IndexOutOfBoundsException obe) {
            // MessagePattern exception, Invalid input string
        }

        return category_map;
    }
}