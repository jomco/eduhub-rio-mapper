require 'English'
require 'open-uri'
require 'json'

PROGRAM_ID = "49ca7998-74b1-f44a-1ec1-000000000002"
ENDPOINT = "jomco.github.io" # ensure this corresponds to institution-schac-home for client

def lookup_env(name)
  ENV[name] or raise "No ENV #{name}"
end

def access_token
  script = %Q(
    curl -s --request POST \
      --url "#{lookup_env('TOKEN_ENDPOINT')}" \
      --header 'content-type: application/x-www-form-urlencoded' \
      --data grant_type=client_credentials \
      --data "audience=#{lookup_env('SURF_CONEXT_CLIENT_ID')}" \
      --user "#{lookup_env('CLIENT_ID')}:#{lookup_env('CLIENT_SECRET')}" \
      | jq -r .access_token
  )
  access_token = `#{script}`.chomp
  exit unless $CHILD_STATUS.success?
  return access_token
end

def education_specification_id(program_id)
  `./dev/ooapi-get.sh #{ENDPOINT} programs/#{program_id} | jq -r '.educationSpecification'`.chomp
end

ACCESS_TOKEN = access_token
EDUCATION_SPECIFICATION_ID = education_specification_id(PROGRAM_ID)
puts "EDUCATION_SPECIFICATION_ID = #{EDUCATION_SPECIFICATION_ID}"

# Now run the same commands through the web API

ENV['API_PORT'] = '2345'
ENV['API_HOSTNAME'] = 'localhost'

api_server_pid = spawn(ENV, 'lein trampoline mapper serve-api')
worker_server_pid = spawn(ENV, 'lein trampoline mapper worker')

at_exit do
  Process.kill('SIGTERM', api_server_pid)
  Process.kill('SIGTERM', worker_server_pid)
end

ROOT_URL = "http://#{ENV['API_HOSTNAME']}:#{ENV['API_PORT']}"

# Give api server some time to startup
def wait_for_server
  30.times do
    return if system("curl -s \"#{ROOT_URL}\"")
    sleep 1
  end
  puts "Timeout waiting for serve-api to come online.."
  exit
end

wait_for_server
puts "serve-api is online.."

TOKENS = {}
JOBS_SPEC = { dry_run_eduspec: ["Dry run eduspec", "job/dry-run-upsert/education-specifications/:education_specification_id", :education_specification_id],
              upsert_eduspec: ["Upsert eduspec", "job/upsert/education-specifications/:education_specification_id", :education_specification_id],
              upsert_program: ["Upsert program", "job/upsert/programs/:program_id", :program_id],
              dry_run_program: ["Dry run program", "job/upsert/programs/:program_id", :program_id],
              link_program: ["Link program", "job/link/:program_id/programs/4c358c84-dfc3-4a30-874e-000000000000", :program_id],
              unlink_program: ["Unlink program", "job/unlink/:program_id/programs", :program_id],
              delete_program: ["Delete program", "job/delete/programs/:program_id", :program_id],
              delete_eduspec: ["Delete eduspec", "job/delete/education-specifications/:education_specification_id", :education_specification_id]}

def start_job(url, key, desc)
  puts "POST #{desc}"
  token = `curl -sf -X POST -H "Authorization: Bearer #{ACCESS_TOKEN}" -H "X-Callback: #{ROOT_URL}/webhook" "#{ROOT_URL}/#{url}" | jq -r .token`.chomp
  exit unless $CHILD_STATUS.success?
  puts "  token=#{token}\n"
  TOKENS[key] = token
end

JOBS_SPEC.each do |key,(desc,url,type)|
  job_url = type == :program_id ? url.sub(":program_id", PROGRAM_ID) : url.sub(":education_specification_id", EDUCATION_SPECIFICATION_ID)
  puts job_url
  start_job(job_url, key, desc)
end

PROCESSED = {}

def check_job(key, desc)
  token = TOKENS[key] or raise "No token for #{key}"
  return if PROCESSED[key]
  url = "#{ROOT_URL}/status/#{token}?http-messages=#{ENV['HTTP_MESSAGES']}"
  puts "Status #{desc}"

  json_string = `curl -sf -H "Authorization: Bearer #{ACCESS_TOKEN}" "#{url}" | jq`.chomp
  status = JSON.parse(json_string)['status']
  puts json_string, "\n"
  PROCESSED[key] = true if status == 'done' || status == 'error'
end

until PROCESSED[:upsert_eduspec] && PROCESSED[:delete_eduspec]
  sleep 5
  JOBS_SPEC.each {|key,(desc,_,type)| check_job(key, desc) }
end
