package frontlinesms2


class SearchControllerIntegrationSpec extends grails.plugin.spock.IntegrationSpec {
	def controller
	def firstContact, secondContact
	def group
	def folder

	def setup() {
		controller = new SearchController()
		firstContact = new Contact(name:'Alex', primaryMobile:'+254987654').save(failOnError:true)
		secondContact = new Contact(name:'Mark', primaryMobile:'+254333222').save(failOnError:true)
		group = new Group(name:'test').save(failOnError:true)
		
		
		[new Fmessage(src:'+254987654', dst:'+254987654', text:'work at 11.00', archived: true),
				new Fmessage(src:'+254111222', dst:'+254937634', text:'work is awesome'),
				new Fmessage(src:'Bob', dst:'+254987654', dateReceived: new Date()-5, text:'hi Bob'),
				new Fmessage(src:'Michael', dst:'+2541234567', dateReceived: new Date()-7,text:'Can we get meet in 5 minutes')].each() {
			it.status = MessageStatus.INBOUND
			it.save(failOnError:true)
			}

		def chickenMessage = new Fmessage(src:'Barnabus', dst:'+12345678', text:'i like chicken', status:MessageStatus.INBOUND).save(failOnError:true)
		def liverMessage = new Fmessage(src:'Minime', dst:'+12345678', text:'i like liver', status: MessageStatus.INBOUND).save(failOnError:true)
		def liverMessage2 = new Fmessage(src:'+254333222', dst:'+12345678', text:'liver for lunch?', status:MessageStatus.INBOUND).save(failOnError:true)
		def chickenResponse = new PollResponse(value:'chicken')
		def liverResponse = new PollResponse(value:'liver')
		liverResponse.addToMessages(liverMessage)
		liverResponse.addToMessages(liverMessage2)
		chickenResponse.addToMessages(chickenMessage)
		new Poll(title:'Miauow Mix', responses:[chickenResponse, liverResponse]).save(failOnError:true)
	}
	
	private def makeGroupMember() {
		firstContact.addToGroups(group)
		secondContact.addToGroups(group)
		firstContact.save(failOnError:true)
		secondContact.save(failOnError:true)
		assert(Contact.get(firstContact.id).isMemberOf(Group.get(group.id)))
		assert(Contact.get(secondContact.id).isMemberOf(Group.get(group.id)))
	}
	
	def "blank search string returns a list of messages"() {
		when:
			def search = new Search(name: "toSave", searchString: "")
			search.save(failOnError: true, flush: true)
			controller.params.search = search
			controller.params.inArchive = true
			def model = controller.result()
		then:
			model.messageInstanceList.size() == 7
	}

	def "message searches can be restricted to a poll"() {
		when:
			controller.params.searchString = "chicken"
			controller.params.activityId = "poll-${Poll.findByTitle('Miauow Mix').id}"
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('Barnabus')]
	}
	
	def "message searches can be restricted to a folder"() {
		when:
			folder = new Folder(name: 'work').save(failOnError:true, flush:true)
			folder.addToMessages(Fmessage.findBySrc('+254111222')).save(failOnError: true, flush:true)
			controller.params.searchString = "work"
			controller.params.activityId = "folder-${folder.id}"
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('+254111222')]
	}
	
	def "search for inbound messages only"() {
		when:
			controller.params.messageStatus = "INBOUND"
			controller.params.inArchive = true
			def model = controller.result()
		then:
			model.messageInstanceTotal == 7
			model.messageInstanceList.every {it.status == MessageStatus.INBOUND}
	}

	def "search for sent messages only"() {
		setup:
			new Fmessage(src: "src", dst: "dst", status: MessageStatus.SEND_PENDING).save(flush: true)
			new Fmessage(src: "src", dst: "dst", status: MessageStatus.SENT).save(flush: true)
			new Fmessage(src: "src", dst: "dst", status: MessageStatus.SEND_FAILED).save(flush: true)
		when:
			controller.params.messageStatus = "SENT, SEND_PENDING, SEND_FAILED"
			def model = controller.result()
		then:
			model.messageInstanceList.size() == 3
			model.messageInstanceList.every {it.status != MessageStatus.INBOUND}
	}

	def "message searches can be restricted to a contact group, and choice is still present after search completes"() {
		given:
			makeGroupMember()
		when:
			controller.params.searchString = "liver"
			controller.params.groupId = Group.findByName('test').id
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('+254333222')]
			controller.params.groupId == Group.findByName('test').id
	}
	
	def "message searches can be restricted to both contact groups and polls"() {
		given:
			makeGroupMember()
		when:
			controller.params.searchString = "liver"
			controller.params.activityId = "poll-${Poll.findByTitle('Miauow Mix').id}"
			controller.params.groupId = Group.findByName('test').id
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('+254333222')]
	}
	
	def "message searches can be restricted to individual contacts"() {
		when:
			controller.params.contactString = "alex"
			controller.params.inArchive = true
			controller.params.searchString = "work"
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('+254987654')]
	}
	
	def "can include archived messages in search (or not)"() {
		when:
			controller.params.searchString = "work"
			controller.params.inArchive = true
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('+254111222'), Fmessage.findBySrc('+254987654')]
			
		when:
			controller.params.searchString = "work"
			controller.params.inArchive = false
			model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('+254111222')]
	}
	
	def "deleted messages do not appear in search results"() {
		when:
			controller.params.searchString = "liver"
			controller.params.activityId = "poll-${Poll.findByTitle('Miauow Mix').id}"
			Fmessage.findBySrc("+254333222").toDelete().save(flush: true)
			def model = controller.result()
		then:
			model.messageInstanceList == [Fmessage.findBySrc('Minime')]
	}

	def "messageInstanceTotal should give a total count of all the messages available"() {
		when:
			controller.params.searchString = "w"
			controller.params.inArchive = true
			controller.params.max = "1"
			controller.params.offset = "0"
			def model = controller.result()
		then:
			model.messageInstanceList.size() == 1
			model.messageInstanceTotal == 3

	}
	
	def "only return message within the specific time range"() {
		when:
			controller.params.startDate = new Date()-4
			controller.params.endDate = new Date()
			def model = controller.result()
		then:
			model.messageInstanceTotal == 4
		when:
			controller.params.startDate = new Date()-6
			controller.params.endDate = new Date()-3
			model = controller.result()
		then:
			model.messageInstanceTotal == 1
		when:
			controller.params.startDate = new Date()-8
			controller.params.endDate = new Date()-3
			model = controller.result()
		then:
			model.messageInstanceTotal == 2
	}
	
	def "only return message with source phone numbers that are starting with specific number"() {
		when:
			controller.params.phoneNumbersStartWith = ""
			def model = controller.result()
		then:
			model.messageInstanceTotal == 6
		when:
			controller.params.phoneNumbersStartWith = "+"
			model = controller.result()
		then:
			model.messageInstanceList.each { message ->
				println "results ${message.src}" 
			}
			model.messageInstanceTotal == 4
		when:
			controller.params.phoneNumbersStartWith ="+2"
			model= controller.result()
		then:
			model.messageInstanceTotal == 3
	}
}
