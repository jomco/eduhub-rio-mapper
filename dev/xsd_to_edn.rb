# Converts DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd to edn format with a map for
# major entities with a list of attributes and their properties. This edn file can
# be read and accessed from the Clojure app. 
# Outputs to STDOUT. Pipe through the pretty-print-edn.sh script to make it readable.

require 'nokogiri'
require 'edn'
require 'awesome_print'

def lookup(name)
  x = $registry[name] and return x
  y = $raw_registry[name] or return
  $registry[name] = reduce_complex_type(y)
end

def simplify_sequence_element(e, ct_name:, kenmerk:, abstract:)
  if e.name == 'choice'
    return {choice: e.children.select(&:element?).map { simplify_sequence_element(_1, ct_name: ct_name, kenmerk: kenmerk, abstract: abstract) }}
  end
  return e.to_xml unless e.name == 'element'
  a = e.attributes

  if a['type']&.value == 'Kenmerk'
    return {kenmerklist: true}
  end
  cardinality = if a['maxOccurs']
    mx = a['maxOccurs'].value.to_i
    mn = a['minOccurs'].value.to_i
    case [mn,mx]
    when [0,1]; :optional
    when [1,1]; :required
    when [0,99]; :zero_or_more
    when [0,999]; :zero_or_more
    when [1,999]; :one_or_more
    else [mn,mx]
    end
  else
    "N/A"
  end
  if a['name'].nil? and a['type'].nil? and a['maxOccurs'].value == '0'
    return
  end
  if ref = a['ref']&.value
    if abstract == 'true'
      return {cardinality: cardinality, type: $name_to_type[ref], ref: ref }
    end
  end
  {name: a['name']&.value, kenmerk: kenmerk, cardinality: cardinality, type: a['type']&.value}
end

def reduce_sequence(children, ct_name, kenmerk, abstract)
  children.select(&:element?).filter_map { simplify_sequence_element(_1, ct_name: ct_name, kenmerk: kenmerk, abstract: abstract) }
end

def apply_kenmerken(list)
  kenmerk,not_kenmerk = list.partition { _1[:kenmerk] }
  not_kenmerk.each_with_object([]) do |h,acc|
    if h[:kenmerklist]
      kenmerk.each { acc << _1 }
    else
      acc << h
    end
  end.uniq
end

def reduce_complex_type(ct)
  raise unless ct.is_a?(Nokogiri::XML::Element)

  ct_name = ct.attributes['name'].value
  return if ct_name.end_with?('_request')
  element_children = ct.children.select(&:element?)
  raise ct.to_xml if element_children.size > 1

  return [] if element_children.empty?

  abstract = ct.attributes['abstract']&.value
  kenmerk = ct_name.start_with?("Kenmerkwaardenbereik_")

  child = element_children[0]
  sequence = if child.name == 'sequence'
    reduce_sequence(child.children, ct_name, kenmerk, abstract)
  else
    raise child.to_xml unless child.name == 'complexContent'
    cc_children = child.children.select(&:element?)
    raise unless cc_children.size == 1
    ext = cc_children[0]

    raise ext.to_xml unless ext.name == 'extension'
    base = ext.attributes['base'].value
    $registry[base] ||= reduce_complex_type($raw_registry[base])
    base_data = $registry[base]
    kname = "Kenmerkwaardenbereik_#{ct_name}"
    base_data += lookup(kname) || []
    ext_children = ext.children.select(&:element?)
    raise ext.to_xml if ext_children.size > 1

    if ext_children.size == 1
      seq = ext_children[0]
      raise seq.to_xml unless seq.name == 'sequence'
      base_data + reduce_sequence(seq.children, ct_name, kenmerk, abstract)
    else
      base_data
    end
  end
end

f = File.read("resources/DUO_RIO_Beheren_OnderwijsOrganisatie_V4.xsd")
d = Nokogiri::XML.parse f
d.remove_namespaces!
d.xpath(".//annotation").each(&:remove)
schema = d.children.first
ct=schema.children.select(&:element?).select { _1.name == 'complexType' }
$name_to_type = schema.children.select(&:element?).select { _1.name == 'element' }.map(&:attributes).map {|a| [a['name'].value, a['type'].value] }.to_h
$raw_registry = ct.each_with_object({}) {|e,h| h[e.attributes['name'].value] = e }
$registry = {}
$registry = ct.each_with_object({}) {|e,h| h[e.attributes['name'].value] ||= reduce_complex_type(e) }

edn = {}
for type in %w(AangebodenHOOpleiding AangebodenHOOpleidingsonderdeel AangebodenParticuliereOpleiding)
  for ext in ["", "Periode", "Cohort"]
    n = type+ext
    edn[n] = apply_kenmerken($registry[n])
  end
end

for type in %w(HoOpleiding HoOnderwijseenhedencluster HoOnderwijseenheid ParticuliereOpleiding)
  for ext in ["", "Periode"]
    n = type+ext
    reg = $registry[n]
    edn[n] = apply_kenmerken(reg)
  end
end
puts edn.to_edn
