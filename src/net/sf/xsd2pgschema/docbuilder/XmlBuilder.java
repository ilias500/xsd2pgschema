/*
    xsd2pgschema - Database replication tool based on XML Schema
    Copyright 2018-2019 Masashi Yokochi

    https://sourceforge.net/projects/xsd2pgschema/

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

package net.sf.xsd2pgschema.docbuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.stream.XMLOutputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamWriter;

import org.xml.sax.SAXException;

import net.sf.xsd2pgschema.PgField;
import net.sf.xsd2pgschema.PgSchema;
import net.sf.xsd2pgschema.PgSchemaException;
import net.sf.xsd2pgschema.PgSchemaUtil;
import net.sf.xsd2pgschema.PgTable;
import net.sf.xsd2pgschema.type.PgHashSize;
import net.sf.xsd2pgschema.xpathparser.XPathCompList;
import net.sf.xsd2pgschema.xpathparser.XPathCompType;
import net.sf.xsd2pgschema.xpathparser.XPathExpr;

/**
 * XML builder.
 *
 * @author yokochi
 */
public class XmlBuilder extends CommonBuilder {

	/** Instance of XMLOutputFactory. */
	public XMLOutputFactory out_factory = XMLOutputFactory.newInstance();

	/** The PostgreSQL data model. */
	private PgSchema schema;

	/** The PostgreSQL root table. */
	private PgTable root_table;

	/** The list of PostgreSQL table. */
	private List<PgTable> tables;

	/** The size of hash key. */
	private PgHashSize hash_size;

	/** The @xsi:schemaLocation value. */
	protected String xsi_schema_location = "";

	/** Whether to append XML declaration. */
	public boolean append_declare = true;

	/** Whether to append namespace declaration. */
	public boolean append_xmlns = true;

	/** Whether to append @xsi:nil="true" for nillable element. */
	public boolean append_nil_elem = true;

	/** Whether to allow fragmented document. */
	public boolean allow_frag = false;

	/** Whether to use line feed code. */
	protected boolean line_feed = true;

	/** The indent offset. */
	protected int indent_offset = PgSchemaUtil.indent_offset;

	/** The XML stream writer. */
	protected XMLStreamWriter writer = null;

	/** The appended namespace declarations. */
	protected HashSet<String> appended_xmlns = new HashSet<String>();

	/** The pending element. */
	protected LinkedList<XmlBuilderPendingElem> pending_elem = new LinkedList<XmlBuilderPendingElem>();

	/** The line feed code character. */
	private final char[] line_feed_code_char = { '\n' };

	/**
	 * Set indent offset.
	 *
	 * @param indent_offset indent offset
	 */
	public void setIndentOffset(String indent_offset) {

		this.indent_offset = Integer.valueOf(indent_offset);

		if (this.indent_offset < 0)
			this.indent_offset = 0;
		else if (this.indent_offset > 4)
			this.indent_offset = 4;

	}

	/**
	 * Set XML compact format.
	 */
	public void setCompact() {

		indent_offset = 0;
		setLineFeed(false);

	}

	/**
	 * Set line feed code.
	 *
	 * @param line_feed whether to use line feed code
	 */
	public void setLineFeed(boolean line_feed) {

		this.line_feed = line_feed;
		line_feed_code = line_feed ? "\n" : "";

	}

	/**
	 * Set XML stream writer.
	 *
	 * @param writer XML stream writer
	 * @param out output stream of XML stream writer
	 */
	public void setXmlWriter(XMLStreamWriter writer, OutputStream out) {

		this.writer = writer;
		this.out = out;

	}

	/**
	 * Return current indent offset.
	 *
	 * @return int indent offset
	 */
	public int getIndentOffset() {
		return indent_offset;
	}

	/**
	 * Write pending elements.
	 *
	 * @param attr_only whether element has attribute only
	 * @throws XMLStreamException the XML stream exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	protected void writePendingElems(boolean attr_only) throws XMLStreamException, IOException {

		XmlBuilderPendingElem elem;

		boolean has_pending_elem;

		while ((elem = pending_elem.pollLast()) != null) {

			has_pending_elem = pending_elem.size() > 0;

			elem.attr_only = has_pending_elem ? false : attr_only;

			elem.write(this);

			if (has_pending_elem)
				writeLineFeedCode();

		}

	}

	/**
	 * Append simple content.
	 *
	 * @param content simple content
	 */
	private void appendSimpleCont(String content) {

		pending_simple_cont.append(content);

	}

	/**
	 * Write pending simple content.
	 *
	 * @throws XMLStreamException the XML stream exception
	 */
	protected void writePendingSimpleCont() throws XMLStreamException {

		if (pending_simple_cont.length() == 0)
			return;

		writer.writeCharacters(pending_simple_cont.toString());

		super.clear();

	}

	/** The OutputStream of XML stream writer. */
	private OutputStream out;

	/**
	 * Write start document.
	 *
	 * @throws XMLStreamException the XML stream exception
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	public void writeStartDocument() throws XMLStreamException, IOException {

		clear();

		if (append_declare) {

			writer.writeStartDocument(PgSchemaUtil.def_encoding, PgSchemaUtil.def_xml_version);

			writeLineFeedCode();

		}

	}

	/**
	 * Write end document.
	 *
	 * @throws XMLStreamException the XML stream exception
	 */
	public void writeEndDocument() throws XMLStreamException {

		if (append_declare)
			writer.writeEndDocument();

	}

	/**
	 * Insert document key.
	 *
	 * @param tag XML start/end element tag template
	 * @param content content
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws XMLStreamException the XML stream exception
	 */
	private void insertDocKey(byte[] tag, String content) throws IOException, XMLStreamException {

		out.write(tag, 1, tag.length - 1);

		writer.writeCharacters(content);

		tag[1] = '/';

		out.write(tag);

		tag[1] = '<';

	}

	/**
	 * Write simple element without consideration of charset.
	 *
	 * @param tag XML start/end element tag template
	 * @param latin_1_encoded whether content is encoded using Latin-1 charset
	 * @param content content
	 * @throws IOException Signals that an I/O exception has occurred.
	 * @throws XMLStreamException the XML stream exception
	 */
	private void writeSimpleElement(byte[] tag, boolean latin_1_encoded, String content) throws IOException, XMLStreamException {

		out.write(tag, 1, tag.length - (line_feed ? 2 : 1));

		if (latin_1_encoded)
			out.write(getSimpleBytes(content)); // content.getBytes(PgSchemaUtil.latin_1_charset));
		else
			writer.writeCharacters(content);

		tag[1] = '/';

		out.write(tag);

		tag[1] = '<';

	}

	/**
	 * Write simple characters assuming charset is ISO-Latin-1.
	 *
	 * @param string string.
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	protected void writeSimpleCharacters(String string) throws IOException {

		out.write(getSimpleBytes(string)); // string.getBytes(PgSchemaUtil.latin_1_charset));

	}

	/**
	 * Return byte array of string assuming charset is ISO-Latin-1.
	 *
	 * @param string string
	 * @return byte[] byte array of string
	 */
	protected byte[] getSimpleBytes(String string) {

		int len = string.length();

		char chars[] = new char[len];
		byte bytes[] = new byte[len];

		string.getChars(0, len, chars, 0);

		for (int j = 0; j < len; j++)
			bytes[j] = (byte) chars[j];

		return bytes;
	}

	/**
	 * Write simple characters.
	 *
	 * @param bytes byte array.
	 * @throws IOException Signals that an I/O exception has occurred.
	 */
	protected void writeSimpleCharacters(byte[] bytes) throws IOException {

		out.write(bytes);

	}

	/**
	 * Write line feed code.
	 *
	 * @throws XMLStreamException the XML stream exception
	 */
	protected void writeLineFeedCode() throws XMLStreamException {

		if (line_feed)
			writer.writeCharacters(line_feed_code_char, 0, 1);

	}

	/**
	 * Clear XML builder.
	 */
	public void clear() {

		super.clear();

		appended_xmlns.clear();

		XmlBuilderPendingElem elem;

		while ((elem = pending_elem.pollLast()) != null)
			elem.clear();

	}

	// XPath evaluation to XML over PostgreSQL

	/**
	 * Initialize XML builder using PostgreSQL data model.
	 *
	 * @param schema PostgreSQL data model
	 */
	public void init(PgSchema schema) {

		this.schema = schema;

		root_table = schema.getRootTable();
		tables = schema.getTableList();
		hash_size = schema.option.hash_size;

		xsi_schema_location = schema.getDefaultNamespace() + " " + PgSchemaUtil.getSchemaFileName(schema.option.root_schema_location);

	}

	/**
	 * Compose XML fragment (field or text node)
	 *
	 * @param xpath_comp_list current XPath component list
	 * @param path_expr current XPath expression
	 * @param rset current result set
	 * @throws PgSchemaException the pg schema exception
	 */
	public void pgSql2XmlFrag(XPathCompList xpath_comp_list, XPathExpr path_expr, ResultSet rset) throws PgSchemaException {

		XPathCompType terminus = path_expr.terminus;

		if (terminus.equals(XPathCompType.table))
			return;

		PgTable table = path_expr.sql_subject.table;

		String table_name = table.xname;
		String table_ns = table.target_namespace;
		String table_prefix = table.prefix;

		PgField field = path_expr.sql_subject.field;

		String field_name = field.xname;
		String field_ns = field.target_namespace;
		String field_prefix = field.prefix;

		try {

			String content;

			while (rset.next()) {

				switch (terminus) {
				case element:
					content = field.retrieveFirst(rset);

					if (content != null) {

						if (field.is_same_namespace_of_table) {

							writer.writeStartElement(table_prefix, field_name, table_ns);

							if (append_xmlns)
								writer.writeNamespace(table_prefix, table_ns);

						}

						else {

							writer.writeStartElement(field_prefix, field_name, field_ns);

							if (append_xmlns)
								writer.writeNamespace(field_prefix, field_ns);

						}

						writer.writeCharacters(content);

						writer.writeEndElement();

						writeLineFeedCode();

					}

					else {

						if (field.is_same_namespace_of_table) {

							writer.writeEmptyElement(table_prefix, field_name, table_ns);

							if (append_xmlns) {
								writer.writeNamespace(table_prefix, table_ns);
								writer.writeNamespace(PgSchemaUtil.xsi_prefix, PgSchemaUtil.xsi_namespace_uri);
							}

						}

						else {

							writer.writeEmptyElement(field_prefix, field_name, field_ns);

							if (append_xmlns) {
								writer.writeNamespace(field_prefix, field_ns);
								writer.writeNamespace(PgSchemaUtil.xsi_prefix, PgSchemaUtil.xsi_namespace_uri);
							}

						}

						writer.writeAttribute(PgSchemaUtil.xsi_prefix, PgSchemaUtil.xsi_namespace_uri, "nil", "true");

						writeLineFeedCode();

					}
					break;
				case simple_content:
					content = field.retrieveFirst(rset);

					// simple content

					if (!field.simple_attribute) {

						if (content != null) {

							writer.writeStartElement(table_prefix, table_name, table_ns);

							if (append_xmlns)
								writer.writeNamespace(table_prefix, table_ns);

							writer.writeCharacters(content);

							writer.writeEndElement();

							writeLineFeedCode();

						}

					}

					// simple attribute

					else {

						PgTable parent_table = xpath_comp_list.getParentTable(path_expr);

						if (content != null) {

							writer.writeEmptyElement(parent_table.prefix, parent_table.xname, parent_table.target_namespace);

							if (append_xmlns)
								writer.writeNamespace(parent_table.prefix, parent_table.target_namespace);

							if (field.is_same_namespace_of_table)
								writer.writeAttribute(field.parent_nodes[0], content);
							else
								writer.writeAttribute(field_prefix, field_ns, field.parent_nodes[0], content);

							writeLineFeedCode();

						}

					}
					break;
				case attribute:
					content = field.retrieveFirst(rset);

					// attribute

					if (field.attribute) {

						if (content != null) {

							writer.writeEmptyElement(table_prefix, table_name, table_ns);

							if (append_xmlns)
								writer.writeNamespace(table_prefix, table_ns);

							if (field_ns.equals(table.target_namespace))
								writer.writeAttribute(field_name, content);
							else
								writer.writeAttribute(field_prefix, field_ns, field_name, content);

							writeLineFeedCode();

						}

					}

					// simple attribute

					else {

						PgTable parent_table = xpath_comp_list.getParentTable(path_expr);

						if (content != null) {

							writer.writeEmptyElement(parent_table.prefix, parent_table.xname, parent_table.target_namespace);

							if (append_xmlns)
								writer.writeNamespace(parent_table.prefix, parent_table.target_namespace);

							if (field.is_same_namespace_of_table)
								writer.writeAttribute(field.parent_nodes[0], content);
							else
								writer.writeAttribute(field_prefix, field_ns, field.parent_nodes[0], content);

							writeLineFeedCode();

						}

					}
					break;
				case any_attribute:
				case any_element:
					Array arrayed_cont = rset.getArray(1);

					String[] contents = (String[]) arrayed_cont.getArray();

					for (String _content : contents) {

						if (_content != null) {

							String path = path_expr.getReadablePath();

							String target_path = getLastNameOfPath(path);

							if (terminus.equals(XPathCompType.any_attribute) || target_path.startsWith("@")) {

								writer.writeEmptyElement(field.prefix, getLastNameOfPath(getParentPath(path)), table_ns);

								if (append_xmlns)
									writer.writeNamespace(field.prefix, field.any_namespace);

								String attr_name = target_path.replace("@", "");

								if (field.prefix.isEmpty() || field.any_namespace.isEmpty())
									writer.writeAttribute(attr_name, _content);
								else
									writer.writeAttribute(field.prefix, field.any_namespace, attr_name, _content);

							}

							else {

								writer.writeStartElement(field.prefix, target_path, field.any_namespace);

								if (append_xmlns)
									writer.writeNamespace(field.prefix, field.any_namespace);

								writer.writeCharacters(_content);

								writer.writeEndElement();

							}

							writeLineFeedCode();

						}

					}
					break;
				case text:
					content = rset.getString(1);

					if (content != null) {

						String column_name = rset.getMetaData().getColumnName(1);

						PgField _field = table.getField(column_name);

						if (_field != null)
							content = field.retrieveFirst(rset);

						writer.writeCharacters(content);

						writeLineFeedCode();

					}
					break;
				case comment:
					content = rset.getString(1);

					if (content != null) {

						writer.writeComment(content);

						writeLineFeedCode();

					}
					break;
				case processing_instruction:
					content = rset.getString(1);

					if (content != null) {

						writer.writeProcessingInstruction(content);

						writeLineFeedCode();

					}
					break;
				default:
					continue;
				}

			}

		} catch (SQLException | XMLStreamException e) {
			throw new PgSchemaException(e);
		}

		incFragment();

	}

	/** The current database connection. */
	private Connection db_conn;

	/** The current document id. */
	private String document_id;

	/** Whether this node has inserted document key. */
	private boolean has_insert_doc_key;

	/** SAX parser for any content. */
	private SAXParser any_sax_parser = null;

	/**
	 * Compose XML document (table node)
	 *
	 * @param db_conn database connection
	 * @param path_expr current XPath expression
	 * @param rset current result set
	 * @throws PgSchemaException the pg schema exception
	 */
	public void pgSql2Xml(Connection db_conn, XPathExpr path_expr, ResultSet rset) throws PgSchemaException {

		XPathCompType terminus = path_expr.terminus;

		if (!terminus.equals(XPathCompType.table))
			return;

		this.db_conn = db_conn;

		PgTable table = path_expr.sql_subject.table;

		try {

			XmlBuilderNestTester nest_test = new XmlBuilderNestTester(table, this);
			XmlBuilderPendingElem elem = new XmlBuilderPendingElem(table, nest_test.current_indent_space, true);
			XmlBuilderPendingAttr attr;

			pending_elem.push(elem);

			if (schema.hasWildCard() && any_sax_parser == null) {

				SAXParserFactory spf = SAXParserFactory.newInstance();
				spf.setValidating(false);
				spf.setNamespaceAware(false);

				any_sax_parser = spf.newSAXParser();

			}

			String content;
			Object key;

			boolean attr_only;
			int n;

			document_id = null;

			has_insert_doc_key = false;

			// document key

			PgField document_key = null;

			if (table.doc_key_pname != null) {

				document_key = table.fields.stream().filter(field -> field.pname.equals(table.doc_key_pname)).findFirst().get();
				document_id = rset.getString(document_key.sql_param_id);

			}

			// attribute, any_attribute

			if (table.has_attrs) {

				for (PgField field : table.attr_fields) {

					if (field.attribute) {

						content = field.retrieve(rset);

						if (content != null) {

							attr = new XmlBuilderPendingAttr(field, content);

							elem = pending_elem.peek();

							if (elem != null)
								elem.appendPendingAttr(attr);
							else
								attr.write(this, null);

							if (!nest_test.has_content)
								nest_test.has_content = true;

						}

					}

					else if (field.any_attribute) {

						SQLXML xml_object = rset.getSQLXML(field.sql_param_id);

						if (xml_object != null) {

							InputStream in = xml_object.getBinaryStream();

							if (in != null) {

								XmlBuilderAnyAttrRetriever any_attr = new XmlBuilderAnyAttrRetriever(table.pname, field, nest_test, this);

								any_sax_parser.parse(in, any_attr);

								any_sax_parser.reset();

								in.close();

							}

							xml_object.free();

						}

					}

					else if (field.nested_key_as_attr) {

						key = rset.getObject(field.sql_param_id);

						if (key != null)
							nest_test.merge(nestChildNode2Xml(table, tables.get(field.foreign_table_id), key, true, nest_test));

					}

				}

			}

			// insert document key

			if (insert_doc_key && document_key != null && !table.equals(root_table)) {
				/*
				if (table.equals(root_table)
					throw new PgSchemaException("Not allowed to insert document key to root element.");
				 */
				if (pending_elem.peek() != null)
					writePendingElems(false);

				writePendingSimpleCont();

				if (!nest_test.has_child_elem)
					writeLineFeedCode();

				writeSimpleCharacters(nest_test.child_indent_bytes);

				insertDocKey(document_key.start_end_elem_tag, document_id);

				nest_test.has_child_elem = nest_test.has_content = has_insert_doc_key = true;

			}

			// simple_content, element, any

			if (table.has_elems) {

				for (PgField field : table.elem_fields) {

					if (field.simple_content && !field.simple_attribute) {

						content = field.retrieve(rset);

						if (content != null) {

							if (pending_elem.peek() != null)
								writePendingElems(false);

							if (field.simple_primitive_list) {

								if (pending_simple_cont.length() > 0) {

									writePendingSimpleCont();

									writer.writeEndElement();

									elem = new XmlBuilderPendingElem(table, (pending_elem.size() > 0 ? "" : line_feed_code) + nest_test.current_indent_space, false);

									elem.write(this);

								}

							}

							appendSimpleCont(content);

							nest_test.has_simple_content = nest_test.has_open_simple_content = true;

						}

					}

					else if (field.element) {

						content = field.retrieve(rset);

						if (content != null || (field.nillable && append_nil_elem)) {

							if (pending_elem.peek() != null)
								writePendingElems(false);

							writePendingSimpleCont();

							if (!nest_test.has_child_elem)
								writeLineFeedCode();

							writeSimpleCharacters(nest_test.child_indent_bytes);

							if (content != null)
								writeSimpleElement(field.start_end_elem_tag, field.latin_1_encoded, content);

							else
								writeSimpleCharacters(field.empty_elem_tag);

							if (!nest_test.has_child_elem)
								nest_test.has_child_elem = true;

							if (!nest_test.has_content)
								nest_test.has_content = true;

							if (has_insert_doc_key)
								has_insert_doc_key = false;

						}

					}

					else if (field.any) {

						SQLXML xml_object = rset.getSQLXML(field.sql_param_id);

						if (xml_object != null) {

							InputStream in = xml_object.getBinaryStream();

							if (in != null) {

								XmlBuilderAnyRetriever any = new XmlBuilderAnyRetriever(table.pname, field, nest_test, this);

								any_sax_parser.parse(in, any);

								any_sax_parser.reset();

								if (has_insert_doc_key)
									has_insert_doc_key = false;

								in.close();

							}

							xml_object.free();

						}

					}

				}

			}

			// nested key

			if (table.total_nested_fields > 0) {

				PgTable nested_table;

				n = 0;

				for (PgField field : table.nested_fields) {

					if (!field.nested_key_as_attr) {

						key = rset.getObject(field.sql_param_id);

						if (key != null) {

							nest_test.has_child_elem |= n++ > 0;

							nested_table = tables.get(field.foreign_table_id);

							if (nested_table.content_holder || !nested_table.bridge)
								nest_test.merge(nestChildNode2Xml(table, nested_table, key, false, nest_test));

							// skip bridge table for acceleration

							else if (nested_table.list_holder)
								nest_test.merge(skipListAndBridgeNode2Xml(nested_table, key, nest_test));

							else
								nest_test.merge(skipBridgeNode2Xml(nested_table, key, nest_test));

						}

					}

				}

			}

			if (nest_test.has_content || nest_test.has_simple_content) {

				attr_only = false;

				if (pending_elem.peek() != null)
					writePendingElems(attr_only = true);

				writePendingSimpleCont();

				if (!nest_test.has_open_simple_content && !attr_only)
					writeSimpleCharacters(nest_test.current_indent_bytes);
				else if (nest_test.has_simple_content)
					nest_test.has_open_simple_content = false;

				if (!attr_only)
					writer.writeEndElement();

				writeLineFeedCode();

			}

			else
				pending_elem.poll();

		} catch (XMLStreamException e) {
			if (insert_doc_key)
				System.err.println("Not allowed insert document key to element has any child element.");
			throw new PgSchemaException(e);
		} catch (SQLException | SAXException | IOException | ParserConfigurationException e) {
			throw new PgSchemaException(e);
		}

		incRootCount();

		appended_xmlns.clear();

	}

	/**
	 * Nest node and compose XML document.
	 *
	 * @param foreign_table foreign table (for simple attribute)
	 * @param table current table
	 * @param parent_key parent key
	 * @param as_attr whether parent key is simple attribute
	 * @param parent_nest_test nest test result of parent node
	 * @return XmlBuilderNestTester nest test of this node
	 * @throws PgSchemaException the pg schema exception
	 */
	private XmlBuilderNestTester nestChildNode2Xml(final PgTable foreign_table, final PgTable table, final Object parent_key, final boolean as_attr, XmlBuilderNestTester parent_nest_test) throws PgSchemaException {

		try {

			XmlBuilderNestTester nest_test = new XmlBuilderNestTester(table, parent_nest_test);
			XmlBuilderPendingElem elem;
			XmlBuilderPendingAttr attr;

			boolean not_virtual = !table.virtual && !as_attr;
			boolean not_list_and_bridge = !table.list_holder && table.bridge;

			boolean category = not_virtual && not_list_and_bridge;
			boolean category_item = not_virtual && !not_list_and_bridge;

			if (category) {

				pending_elem.push(new XmlBuilderPendingElem(table, (parent_nest_test.has_child_elem || pending_elem.size() > 0 ? (has_insert_doc_key ? line_feed_code : "") : line_feed_code) + nest_test.current_indent_space, true));

				if (has_insert_doc_key)
					has_insert_doc_key = false;

			}

			boolean use_doc_key_index = document_id != null && !table.has_unique_primary_key;
			boolean use_primary_key = !use_doc_key_index || table.list_holder || table.virtual || table.has_simple_content || table.total_foreign_fields > 1;
			boolean attr_only;

			PreparedStatement ps = table.ps;

			if (ps == null) {

				String sql = "SELECT * FROM " + table.pgname + " WHERE " + (use_doc_key_index ? table.doc_key_pgname + " = ?" : "") + (use_primary_key ? (use_doc_key_index ? " AND " : "") + table.primary_key_pgname + " = ?" : "");

				ps = table.ps = db_conn.prepareStatement(sql);
				ps.setFetchSize(PgSchemaUtil.pg_min_rows_for_index);

			}

			if (use_doc_key_index)
				ps.setString(1, document_id);

			if (use_primary_key) {

				int param_id = use_doc_key_index ? 2 : 1;

				switch (hash_size) {
				case native_default:
					ps.setBytes(param_id, (byte[]) parent_key);
					break;
				case unsigned_int_32:
					ps.setInt(param_id, (int) (parent_key));
					break;
				case unsigned_long_64:
					ps.setLong(param_id, (long) parent_key);
					break;
				default:
					throw new PgSchemaException("Not allowed to use string hash key (debug mode) for XPath evaluation.");
				}

			}

			ResultSet rset = ps.executeQuery();

			String content;

			PgTable nested_table;

			Object key;

			int list_id = 0, n;

			while (rset.next()) {

				if (category_item) {

					pending_elem.push(new XmlBuilderPendingElem(table, (parent_nest_test.has_child_elem || pending_elem.size() > 0 || list_id > 0 ? (has_insert_doc_key ? line_feed_code : "") : line_feed_code) + nest_test.current_indent_space, true));

					if (has_insert_doc_key)
						has_insert_doc_key = false;

					if (!table.bridge)
						nest_test.has_child_elem = false;

				}

				// attribute, simple attribute, any_attribute

				if (table.has_attrs) {

					for (PgField field : table.attr_fields) {

						if (field.attribute) {

							content = field.retrieve(rset);

							if (content != null) {

								attr = new XmlBuilderPendingAttr(field, content);

								elem = pending_elem.peek();

								if (elem != null)
									elem.appendPendingAttr(attr);
								else
									attr.write(this, null);

								if (!nest_test.has_content)
									nest_test.has_content = true;

							}

						}

						else if ((field.simple_attribute || field.simple_attr_cond) && as_attr) {

							content = field.retrieve(rset);

							if (content != null) {

								attr = new XmlBuilderPendingAttr(field, foreign_table, content);

								elem = pending_elem.peek();

								if (elem != null)
									elem.appendPendingAttr(attr);
								else
									attr.write(this, null);

								nest_test.has_content = true;

							}

						}

						else if (field.any_attribute) {

							SQLXML xml_object = rset.getSQLXML(field.sql_param_id);

							if (xml_object != null) {

								InputStream in = xml_object.getBinaryStream();

								if (in != null) {

									XmlBuilderAnyAttrRetriever any_attr = new XmlBuilderAnyAttrRetriever(table.pname, field, nest_test, this);

									any_sax_parser.parse(in, any_attr);

									any_sax_parser.reset();

									in.close();

								}

								xml_object.free();

							}

						}

						else if (field.nested_key_as_attr) {

							key = rset.getObject(field.sql_param_id);

							if (key != null)
								nest_test.merge(nestChildNode2Xml(foreign_table, tables.get(field.foreign_table_id), key, true, nest_test));

						}

					}

				}

				// simple_content, element, any

				if (table.has_elems) {

					for (PgField field : table.elem_fields) {

						if (field.simple_content && !field.simple_attribute && !as_attr) {

							content = field.retrieve(rset);

							if (content != null) {

								if (pending_elem.peek() != null)
									writePendingElems(false);

								if (field.simple_primitive_list) {

									if (pending_simple_cont.length() > 0) {

										writePendingSimpleCont();

										writer.writeEndElement();

										elem = new XmlBuilderPendingElem(table, (parent_nest_test.has_child_elem || pending_elem.size() > 0 || list_id > 0 ? "" : line_feed_code) + nest_test.current_indent_space, false);

										elem.write(this);

									}

								}

								appendSimpleCont(content);

								nest_test.has_simple_content = nest_test.has_open_simple_content = true;

							}

						}

						else if (field.element) {

							content = field.retrieve(rset);

							if (content != null || (field.nillable && append_nil_elem)) {

								if (pending_elem.peek() != null)
									writePendingElems(false);

								writePendingSimpleCont();

								if (!nest_test.has_child_elem)
									writeLineFeedCode();

								writeSimpleCharacters(nest_test.child_indent_bytes);

								if (content != null)
									writeSimpleElement(field.start_end_elem_tag, field.latin_1_encoded, content);

								else
									writeSimpleCharacters(field.empty_elem_tag);

								if (!nest_test.has_child_elem)
									nest_test.has_child_elem = true;

								if (!nest_test.has_content)
									nest_test.has_content = true;

								if (has_insert_doc_key)
									has_insert_doc_key = false;

							}

						}

						else if (field.any) {

							SQLXML xml_object = rset.getSQLXML(field.sql_param_id);

							if (xml_object != null) {

								InputStream in = xml_object.getBinaryStream();

								if (in != null) {

									XmlBuilderAnyRetriever any = new XmlBuilderAnyRetriever(table.pname, field, nest_test, this);

									any_sax_parser.parse(in, any);

									any_sax_parser.reset();

									if (has_insert_doc_key)
										has_insert_doc_key = false;

									in.close();

								}

								xml_object.free();

							}

						}

					}

				}

				// nested key

				if (table.total_nested_fields > 0) {

					n = 0;

					for (PgField field : table.nested_fields) {

						if (!field.nested_key_as_attr) {

							key = rset.getObject(field.sql_param_id);

							if (key != null) {

								nest_test.has_child_elem |= n++ > 0;

								nested_table = tables.get(field.foreign_table_id);

								if (nested_table.content_holder || !nested_table.bridge || as_attr)
									nest_test.merge(nestChildNode2Xml(table, nested_table, key, false, nest_test));

								// skip bridge table for acceleration

								else if (nested_table.list_holder)
									nest_test.merge(skipListAndBridgeNode2Xml(nested_table, key, nest_test));

								else
									nest_test.merge(skipBridgeNode2Xml(nested_table, key, nest_test));

							}

						}

					}

				}

				if (category_item) {

					if (nest_test.has_content || nest_test.has_simple_content) {

						attr_only = false;

						if (pending_elem.peek() != null)
							writePendingElems(attr_only = true);

						writePendingSimpleCont();

						if (!nest_test.has_open_simple_content && !attr_only)
							writeSimpleCharacters(nest_test.current_indent_bytes);
						else if (nest_test.has_simple_content)
							nest_test.has_open_simple_content = false;

						if (!attr_only)
							writer.writeEndElement();

						writeLineFeedCode();

					}

					else
						pending_elem.poll();

					list_id++;

				}

			}

			rset.close();

			if (category) {

				if (nest_test.has_content || nest_test.has_simple_content) {

					attr_only = false;

					if (pending_elem.peek() != null)
						writePendingElems(attr_only = true);

					writePendingSimpleCont();

					if (!nest_test.has_open_simple_content && !attr_only)
						writeSimpleCharacters(nest_test.current_indent_bytes);
					else if (nest_test.has_simple_content)
						nest_test.has_open_simple_content = false;

					if (!attr_only)
						writer.writeEndElement();

					writeLineFeedCode();

				}

				else
					pending_elem.poll();

			}

			return nest_test;

		} catch (SQLException | XMLStreamException | SAXException | IOException e) {
			throw new PgSchemaException(e);
		}

	}

	/**
	 * Skip list holder and bridge node and continue to compose XML document.
	 *
	 * @param table list holder and bridge table
	 * @param parent_key parent key
	 * @param parent_nest_test nest test result of parent node
	 * @return XmlBuilderNestTester nest test of this node
	 * @throws PgSchemaException the pg schema exception
	 */
	private XmlBuilderNestTester skipListAndBridgeNode2Xml(final PgTable table, final Object parent_key, XmlBuilderNestTester parent_nest_test) throws PgSchemaException {

		try {

			XmlBuilderNestTester nest_test = new XmlBuilderNestTester(table, parent_nest_test);

			boolean category_item = !table.virtual;

			boolean use_doc_key_index = document_id != null && !table.has_unique_primary_key;
			boolean attr_only;

			PreparedStatement ps = table.ps;

			PgField nested_key = table.nested_fields.stream().filter(field -> !field.nested_key_as_attr).findFirst().get();
			PgTable nested_table = tables.get(nested_key.foreign_table_id);

			if (ps == null) {

				String sql = "SELECT " + PgSchemaUtil.avoidPgReservedWords(nested_key.pname) + " FROM " + table.pgname + " WHERE " + (use_doc_key_index ? table.doc_key_pgname + " = ?" : "") + (use_doc_key_index ? " AND " : "") + table.primary_key_pgname + " = ?";

				ps = table.ps = db_conn.prepareStatement(sql);
				ps.setFetchSize(PgSchemaUtil.pg_min_rows_for_index);

			}

			if (use_doc_key_index)
				ps.setString(1, document_id);

			int param_id = use_doc_key_index ? 2 : 1;

			switch (hash_size) {
			case native_default:
				ps.setBytes(param_id, (byte[]) parent_key);
				break;
			case unsigned_int_32:
				ps.setInt(param_id, (int) (parent_key));
				break;
			case unsigned_long_64:
				ps.setLong(param_id, (long) parent_key);
				break;
			default:
				throw new PgSchemaException("Not allowed to use string hash key (debug mode) for XPath evaluation.");
			}

			ResultSet rset = ps.executeQuery();

			Object key;

			int list_id = 0;

			while (rset.next()) {

				if (category_item) {

					pending_elem.push(new XmlBuilderPendingElem(table, (parent_nest_test.has_child_elem || pending_elem.size() > 0 || list_id > 0 ? (has_insert_doc_key ? line_feed_code : "") : line_feed_code) + nest_test.current_indent_space, true));

					if (has_insert_doc_key)
						has_insert_doc_key = false;

				}

				// nested key

				key = rset.getObject(1);

				if (key != null) {

					if (nested_table.content_holder || !nested_table.bridge)
						nest_test.merge(nestChildNode2Xml(table, nested_table, key, false, nest_test));

					// skip bridge table for acceleration

					else if (nested_table.list_holder)
						nest_test.merge(skipListAndBridgeNode2Xml(nested_table, key, nest_test));

					else
						nest_test.merge(skipBridgeNode2Xml(nested_table, key, nest_test));

				}

				if (category_item) {

					if (nest_test.has_content || nest_test.has_simple_content) {

						attr_only = false;

						if (pending_elem.peek() != null)
							writePendingElems(attr_only = true);

						writePendingSimpleCont();

						if (!nest_test.has_open_simple_content && !attr_only)
							writeSimpleCharacters(nest_test.current_indent_bytes);
						else if (nest_test.has_simple_content)
							nest_test.has_open_simple_content = false;

						if (!attr_only)
							writer.writeEndElement();

						writeLineFeedCode();

					}

					else
						pending_elem.poll();

					list_id++;

				}

			}

			rset.close();

			return nest_test;

		} catch (SQLException | XMLStreamException | IOException e) {
			throw new PgSchemaException(e);
		}

	}

	/**
	 * Skip bridge node and continue to compose XML document.
	 *
	 * @param table bridge table
	 * @param parent_key parent key
	 * @param parent_nest_test nest test result of parent node
	 * @return XmlBuilderNestTester nest test of this node
	 * @throws PgSchemaException the pg schema exception
	 */
	private XmlBuilderNestTester skipBridgeNode2Xml(final PgTable table, final Object parent_key, XmlBuilderNestTester parent_nest_test) throws PgSchemaException {

		try {

			XmlBuilderNestTester nest_test = new XmlBuilderNestTester(table, parent_nest_test);

			boolean category = !table.virtual;

			PgField nested_key = table.nested_fields.stream().filter(field -> !field.nested_key_as_attr).findFirst().get();
			PgTable nested_table = tables.get(nested_key.foreign_table_id);

			if (category) {

				pending_elem.push(new XmlBuilderPendingElem(table, (parent_nest_test.has_child_elem || pending_elem.size() > 0 ? (has_insert_doc_key ? line_feed_code : "") : line_feed_code) + nest_test.current_indent_space, true));

				if (has_insert_doc_key)
					has_insert_doc_key = false;

			}

			if (nested_table.content_holder || !nested_table.bridge)
				nest_test.merge(nestChildNode2Xml(table, nested_table, parent_key, false, nest_test));

			// skip bridge table for acceleration

			else if (nested_table.list_holder)
				nest_test.merge(skipListAndBridgeNode2Xml(nested_table, parent_key, nest_test));

			else
				nest_test.merge(skipBridgeNode2Xml(nested_table, parent_key, nest_test));

			if (category) {

				if (nest_test.has_content || nest_test.has_simple_content) {

					boolean attr_only = false;

					if (pending_elem.peek() != null)
						writePendingElems(attr_only = true);

					writePendingSimpleCont();

					if (!nest_test.has_open_simple_content && !attr_only)
						writeSimpleCharacters(nest_test.current_indent_bytes);
					else if (nest_test.has_simple_content)
						nest_test.has_open_simple_content = false;

					if (!attr_only)
						writer.writeEndElement();

					writeLineFeedCode();

				}

				else
					pending_elem.poll();

			}

			return nest_test;

		} catch (XMLStreamException | IOException e) {
			throw new PgSchemaException(e);
		}

	}

}
