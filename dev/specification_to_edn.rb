%w(nokogiri open-uri edn).each(&method(:require))

# curl -s 'https://raw.githubusercontent.com/open-education-api/specification/master/docs/consumers/rio.md' | jq --slurp --raw-input  '{"text": "\(.)", "mode": "markdown"}' | curl -s --data @- https://api.github.com/markdown | ruby dev/specification_to_edn.rb | ./dev/pretty-print-edn.sh > resources/ooapi-specs.edn
def find_preceding_heading(element, heading_name)
  heading = nil
  element.parent.children.select do |c|
    c.element? && (c.name == heading_name || c.name == element.name)
  end.each_cons(2) {|a,b| if b == element; heading = a; break; end }
  heading
end

def find_any_preceding_heading(element, heading_name)
  heading = nil
  element.parent.children.select do |c|
    c.element? && (c.name == heading_name || c.name == element.name)
  end.each {|e| if e.name == heading_name; heading = e; end; break if e == element }
  heading
end

def table_to_hash(table, h)
  h3 = nil
  h3 = find_preceding_heading(table, "h3")
  if h3.nil?
    h3 = find_preceding_heading(table.parent, "h3")
  end
  h2 = nil
  h2 = find_any_preceding_heading(table, "h2")
  if h2.nil?
    h2 = find_any_preceding_heading(table.parent, "h2")
  end
  thead,tbody = table.children.select(&:element?)
  raise if thead.name != 'thead'
  raise if tbody.name != 'tbody'
  table_headers = thead.children.select(&:element?).first.children.select(&:element?).map(&:content)
  trs = tbody.children.select { _1.element? && _1.name == 'tr' }
  rows = trs.map {|tr| tr.children.select { _1.element? && _1.name == 'td' }.map(&:content)}
  h2_header = h2.content.strip
  h3_header = h3.content.strip
  if "Enumeration mappings" == h2_header and rows.all? { _1.size == 2 }
    rows = rows.to_h
    h[:mappings] ||= {}
    h[:mappings][h3_header.split(' › ').last] = rows.to_h
    return
  elsif "Enumeration mappings" == h2_header and rows.all? { _1.size == 3 } and table_headers[2].downcase == "remarks"
    rows = rows.map {|a,b,_rm| [a,b] }.to_h
    h[:mappings] ||= {}
    h[:mappings][h3_header.split(' › ').last] = rows.to_h
    return
  elsif h2_header != "Enumeration mappings"
    rows = rows.map do |a,b,c,d|
      r = if b.end_with?(" [1]")
        b = b[0...-4]
        "required"
      elsif b.end_with?(" [0..1]")
        b = b[0...-7]
        "optional"
      end
      {ooapi: a, rio: b, required: r, remarks: d}
    end
  end

  h[h3.content] = {headers: table_headers, rows: rows}
end

html = ''
while h = gets; html << h; end
tables = Nokogiri::HTML.parse(html).css('table')
h = {}
tables.each { table_to_hash(_1, h) }
puts({mappings: h[:mappings]}.to_edn)
